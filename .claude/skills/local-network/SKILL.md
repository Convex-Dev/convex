---
name: local-network
description: Run isolated local Convex networks and in-process lattice node networks for development. Use when testing against local peers, reproducing peer or P2P replication issues, exercising NodeServer or P2PNode sync, or when no remote network is configured.
---

# Run a Local Convex Network

A local network is the default way to exercise changes in this repository. It
needs no credentials, no remote host, and can be thrown away and recreated
freely. Prefer it over a remote network for anything that is not specifically
about remote behaviour.

Requires a built `convex.jar` — see the `build-convex` skill.

## Start a Temporary Network

```bash
java -jar convex.jar local start
```

Starts a throwaway test network. State is not preserved between runs, which is
what you want for testing.

Useful options:

| Option | Effect |
|--------|--------|
| `--count N` | Number of peers to launch |
| `--ports ...` | Specific peer ports (default: assigned automatically) |
| `--api-port N` | Port for the REST API |
| `--norest` | Do not start the REST server |
| `--no-tray` | No system tray icon |
| `--protocol-version N` | Pin the protocol version |

## Start with the Peer Manager GUI

```bash
java -jar convex.jar local gui
```

Launches the same local network under the peer manager GUI — useful for
watching consensus and inspecting peer state visually.

## Talking to It

Once running, point the client commands at the local peer:

```bash
java -jar convex.jar client query --host localhost --port <PORT> '(balance #12)'
java -jar convex.jar client status --host localhost --port <PORT>
```

## Notes for Tests

Do **not** start a network from a JUnit test by shelling out to the CLI. Tests
construct peers or lattice nodes in-process.

For consensus/CVM peer tests, follow the fixtures in `convex-peer`. For lattice
replication, use `NodeServer`; for the bundled P2P and social regions, use
`P2PNode`. See `convex-peer/src/test/java/convex/node/LatticeNetworkTest.java`
and `convex-p2p/src/test/java/convex/p2p/P2PSocialSyncTest.java`.

`NodeServer` is the schema-independent CAD036 authoritative lattice host. It
owns merge, node-root persistence, the shared listener and isolated update
notification, but does not publish or interpret `NodeInfo`, inspect `:p2p` /
`:social` paths, or implement discovery policy. The calling application must
construct and configure every `LatticePropagator` before attaching it; there is
no implicit/default group. A zero-propagator node is a valid local persistent
host. Each propagator owns its connection manager, transport identity, trust,
bounded protocol endpoint, filters and serving store.

`P2PNode` performs this composition; its `NodeDirectory` owns
signed `[:p2p :nodes]` publication, validation, discovery translation and PoP
metadata. Keep tests for those behaviours in `convex-p2p`, not `convex-peer`.
`P2PNode` also configures its node key explicitly as both its transport challenge
identity and its signed `NodeInfo` owner. A generic `NodeServer` does not infer a
transport key or propagator merge context from `LatticeContext`; direct tests
must set these on the propagator itself before `addPropagator`. Social user DIDs
and their account keys remain a separate application concern.

For a small lattice network test:

> **CAD036 envelopes:** use `[:LV path value]` for an optimistic push with no
> acknowledgement, and `[:LV id path value]` when the sender needs a Result after
> merge. `Message.withID` upgrades the optimistic form without replacing its path.
> Lattice gossip and root sync use the lean optimistic form. Every `:LV` and `:LQ`
> path is a vector, with `[]` selecting the root; do not use nil, omitted or scalar
> paths. A four-field `:LV` with a nil ID remains an accepted fire-and-forget wire
> representation, but is not needed for new optimistic pushes.

> **Security model:** P2P data is public, so `serveAllInbound()` may assign an
> untrusted inbound connection to the public propagator view. Assignment is not
> authentication and does not make the connection an outbound gossip route. An
> unverified connection may submit only complete `LATTICE_VALUE` messages; path-aware
> admission runs before persistence, unsolicited `DATA` is rejected, and missing-cell
> acquisition is reserved for verified connections. P2PNode's default social policy
> retains only local users, operator pins and direct active follows. The separate
> inbound upgrade verifies both challenge signatures, a random nonce, responder and
> challenger audiences, the fixed lattice-peer context and an admitted signed
> NodeInfo. Shared-listener connections are bounded by `NodeConfig`; each
> propagation group's desired peers, protocol state and queues are bounded by
> `LatticePropagatorConfig`.

For a direct `NodeServer` network test, the minimum composition is explicit:

```java
NodeConfig nodeConfig = NodeConfig.localNetwork();
LatticePropagatorConfig groupConfig = LatticePropagatorConfig.create();
NodeServer<V> node = new NodeServer<>(lattice, nodeStore, nodeConfig);
node.setMergeContext(nodeContext);
LatticePropagator group = new LatticePropagator(
    servingStore, lattice, value -> value, groupConfig);
group.setMergeContext(groupContext);
group.setTransportKeyPair(nodeKey);
node.addPropagator(group);
node.setInboundPropagatorSelector(connection -> group);
node.launch();
```

Retain `group` and pass it to explicit pull/route APIs. Do not recover it from
the node merely to treat the first group as a privileged "primary" group.

1. Give each node its own store and key pair.
2. Use `NodeConfig.localNetwork()`. The generic server binds port `0`; after it
   reports the actual OS-assigned port, `P2PNode` publishes that loopback endpoint
   in the node's signed `NodeInfo`.
3. Set an inbound propagator policy before launch. `P2PNode.serveAllInbound()` is
   suitable for a deliberately public test node.
4. Tell one node about the other with
   `nodeA.connect(nodeBKey, nodeB.getNodeServer().getHostAddress())`. The future
   completes after B proves its node key, A's own signed `[:p2p :nodes]` record
   has been merged by B, and A has pulled and merged B's `:p2p`, `:id`, and
   currently desired complete social-owner paths.
   B discovers A from the path-scoped identity update, challenges A on the same
   full-duplex socket, then explicitly upgrades that inbound connection to an
   authenticated outbound propagation route. The bootstrap never pulls a peer's
   complete root and social admission remains follow-filtered.
   Use `nodeB.whenInboundConnectionUpgraded(nodeAKey)` when a test must wait for
   that distinct reverse-route capability before publishing in both directions.
   For three nodes, a useful discovery topology is to tell both leaves only
   about one rendezvous node. Wait until its signed registry has reached both
   leaves, then use `whenConnected` to prove the leaves discovered each other
   without another configured endpoint.
   To test late joining, converge the initial nodes before creating the newcomer,
   tell only the newcomer about the rendezvous node, and require `connect()` itself
   to deliver the existing state without a manual rendezvous-node `sync()`.
   To model a NATed late joiner, use `NodeConfig.port(-1)`: it starts no listener
   and publishes signed NodeInfo with empty `:transports`. Do not call
   `serveAllInbound()` on that leaf. Prove reverse propagation with an application
   write made after the rendezvous node's upgrade future completes; a bootstrap pull
   alone does not prove that the original outbound socket carries traffic both ways.
   For Point of Presence routing, configure each outbound-only leaf with
   `pointsOfPresence(relayKey)` before launch and opt the public node in with
   `relayMessages()`. Connect both leaves only to that relay, await both inbound
   upgrade futures on the relay, then complete a destination-side message-handler
   future from `sendMessage` or `sendPrivateMessage`. Point messages are transient
   and need no application-root `sync()`. Include a wrong-key signature case before
   a valid message on the same ordered route when testing relay authentication.
5. After an application write, call the root application's `sync()` to publish
   the authoritative node root. This schedules each propagator independently;
   its publication filter and serving-store materialisation run on that group's
   worker.
   Use the social test's independent filtered groups when testing this boundary:
   `P2PSocialSyncTest` retains the ordinary follow-aware P2P group while Bob also
   serves an infrastructure-only view from another store. A deliberately broken
   third view proves that `getStatus()` / `nextFailure()` report degradation while
   the node and healthy views continue.
   When batching several edits for one signed social owner, fork the
   `SocialUser`, apply its feed and follow actions, sync that fork once, then
   sync the application root. A `Social` fork is outside the owner boundary and
   therefore still signs each user edit inside the unpublished fork.
6. Automatic gossip is fire-and-forget. To verify it without sleeping, capture
   `nextAnnounce()` before publishing and re-arm it until the expected application
   state is present. The announce signals that this propagation group has
   materialised its served view after the authoritative merge and node-root
   publication. `cursor.sync()` itself guarantees only the authoritative node
   publication; do not assume it completed group fan-out. Use an explicit
   `receivingServer.pull(group, connection).get(timeout)`
   only when the test is specifically about pull synchronisation. A ping only
   establishes transport ordering and does not prove acquisition is complete.
7. Close nodes before closing their stores.

Host and propagation configuration are deliberately independent. Use
`NodeConfig` for the shared listener and authoritative persistence, and
`LatticePropagatorConfig` for each group's routes, protocol queue and publication
limits. `LatticePropagatorConfig.from(nodeConfig)` exists only to migrate old
combined-map callers; do not use it in new tests merely because both objects use
the same underlying CAD map representation.

Treat a node key as its P2P/transport signer, not automatically as an
application user's identity or signing key. For an `OwnerLattice` keyed by an
indirect owner such as a DID, install a fail-closed owner verifier in the
`LatticeContext`; without one, indirect owners use the compatibility-lenient
fallback. Give identity-sensitive tests separate node keys and application
owner/signing keys. The social cursor API accepts canonical base DIDs and uses
`DIDKeyAuthorizer` for the signer binding. Cover `did:key`, `did:convex` and
`did:web` with pinned local state or deterministic resolver fixtures—never depend
on public web resolution in a unit test. `serveAllInbound()` controls network
access and does not replace DID owner authorisation.

Remember that an `AccountKey` is a typed JVM view over a canonical 32-byte
Blob. A key stored as ordinary CAD3 application data can therefore decode as a
Blob. Domain readers should parse compatible Blob values with
`AccountKey.parse` or `AccountKey.create`; do not use `instanceof AccountKey`
as a wire-format validity check.

The rules in `AGENTS.md` apply: never bind fixed ports and never sleep. Wait on
futures, latches or another API whose contract represents the required state.

Stop the network when finished — it holds ports and a temporary store.
