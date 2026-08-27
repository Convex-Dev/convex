# Points of Presence

Points of Presence (PoPs) give outbound-only P2P nodes a return path. A node
connects to one or more public nodes, advertises those node keys in its signed
`NodeInfo`, and can then receive point-to-point messages through a willing PoP.

This is node routing, not social identity. Destinations, PoP declarations and
envelope signers are node `AccountKey` values. Social users remain DIDs with
independently authorised signing keys.

## NodeInfo

Two fields extend the existing signed, LWW `NodeInfo` map:

- `:pops` is a vector of up to 16 unique PoP node keys. It declares an expected
  return path but does not itself open or authenticate a connection.
- `:relay` is a Boolean. Only `true` advertises willingness to forward point
  messages; absence means `false` for compatibility with older records.

An outbound-only node normally publishes an empty `:transports` vector and one
or more `:pops`. It must then establish the declared connections in the normal
way. Challenge/response upgrades each connection independently of the
advertisement.

```java
P2PNode leaf = P2PNode.create(leafStore, NodeConfig.port(-1), leafKey)
    .pointsOfPresence(relayKey.getAccountKey());

P2PNode relay = P2PNode.create(relayStore, NodeConfig.localNetwork(), relayKey)
    .serveAllInbound()
    .relayMessages();

relay.launch();
leaf.launch();
leaf.connect(relayKey.getAccountKey(), relay.getNodeServer().getHostAddress()).join();
```

`serveAllInbound()` is shown because this relay intentionally accepts public
lattice bootstrap traffic, including the leaf's signed NodeInfo. A deployment
may instead install a narrower inbound propagator policy. `relayMessages()` is a
separate opt-in capability.

## Message format

A wire message is a complete CAD3 vector:

```text
[:POP SignedData<Envelope> [origin-node ... previous-hop]]
```

The signed envelope is:

```text
["convex-pop-message-v1" version destination nonce issued-at expires-at
 max-hops encrypted? body]
```

The signer embedded in `SignedData` is the end-to-end sender. Relays never
replace this signature or change the envelope. The destination validates the
same signature regardless of the path used.

The path is deliberately outside the signature because each honest relay adds
itself. It is useful for loop avoidance and checking an authenticated previous
hop, but is not treated as an authority claim. Replay detection uses the hash of
the signed envelope, so rewriting the path cannot create a fresh message.

## Routing

For each valid message, a node:

1. Delivers immediately when the signed destination is its own node key.
2. Otherwise drops the message unless relay service is enabled.
3. Uses a direct authenticated route to the destination when available.
4. Otherwise selects up to three connected nodes advertising `:relay true`,
   preferring paths visible through the bounded `:pops` graph.

Only connections that have proved the expected remote node key are eligible as
outbound relay routes. An operator-assigned but untrusted inbound connection is
not enough. A valid signed message may arrive over an untrusted public
connection, because the envelope supplies end-to-end authentication, but any
forwarding still leaves on an authenticated route.

`sendMessage` and `sendPrivateMessage` return `true` when at least one first-hop
queue accepted the message. They are fire-and-forget and do not currently
provide an end-to-end receipt.

```java
destination.setMessageHandler(message -> {
    AccountKey authenticatedSender = message.sender();
    ACell value = message.value();
});

source.sendMessage(destinationKey, Strings.create("public"));
source.sendPrivateMessage(destinationKey, Strings.create("private"));
```

## Private bodies

`sendPrivateMessage` completely CAD3-encodes the value and encrypts it to the
destination node key with the existing ECIES wrapper (HPKE Base mode over the
Ed25519-to-X25519 conversion). The outer envelope, including sender,
destination, expiry and ciphertext, is then signed. Relays can route the
message but cannot read or alter the body.

This protects a message to a node key. Application protocols that need
user-level encryption should encrypt to a user-controlled key before handing
the ciphertext to this layer.

## Bounds and trust

The initial protocol applies the following hard bounds:

| Limit | Value |
|---|---:|
| Complete encoded message | 256 KiB |
| Lifetime created by the API | 60 seconds |
| Maximum accepted lifetime | 5 minutes |
| Clock lead | 30 seconds |
| Hops | 8 |
| Relay fan-out | 3 |
| Replay cache | 4,096 signed envelopes |
| Inbound rate per connection | 64 messages/second |
| Inbound rate per node | 1,024 messages/second |
| PoPs per NodeInfo | 16 |

Malformed formats, invalid signatures, duplicate paths, expiry violations and
recent replays are rejected on the node's bounded inbound dispatcher. Repeated
rejections contribute to the existing per-connection circuit-breaker. No point
message is persisted in the lattice or grants authority to update a feed,
follow list or other lattice value; those merges retain their own owner and
signature checks.

## Direct hole punching

Direct hole punching is intentionally not part of this first layer. It can be
added later as a route optimisation: PoPs may introduce two authenticated nodes
and exchange observed endpoint candidates, after which the nodes attempt a
direct transport. Failure must leave the relayed route usable, and a successful
direct connection must still pass the normal node-key challenge before it is
trusted.
