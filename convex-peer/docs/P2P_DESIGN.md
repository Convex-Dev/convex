# Convex Peer Network Design

## 1. Overview

This document describes the design of the Convex peer-to-peer network layer. The network enables peers to discover each other, establish authenticated connections over multiple transports, synchronise consensus state, and serve client requests.

The network is built on **lattice technology** — the same algebraic foundation (join-semilattices, CRDTs, cryptographic security) that underlies the entire Convex ecosystem. The P2P network itself is a **lattice region**, meaning peer discovery and metadata propagation use the same merge semantics, delta transmission, and structural sharing as every other part of the lattice.

The core design principle is **separation of the message protocol from the transport layer**. All transports carry the same CAD3-encoded binary protocol messages — the transport is simply the delivery mechanism.

## 2. Lattice Regions

The Convex lattice is divided into **regions** (Consensus, Data, DLFS, Execution, P2P, etc.), each defined by its lattice values and merge rules. See [Lattice Technology](https://docs.convex.world/docs/overview/lattice) for the full description of all regions.

A peer node may participate in any subset of regions based on its role and configuration (**selective attention**). The **P2P Lattice** region, described in this document, is the foundational region that enables discovery and connectivity for all others.

## 3. The P2P Lattice Region

The P2P Lattice is the foundational region that enables all other lattice communication. It solves the problem of locating and connecting participants in a decentralised network.

### 3.1 Lattice Structure

The P2P Lattice value is a **map of Ed25519 public keys (AccountKey) to SignedData\<PeerInfo\>**:

```clojure
;; P2P Lattice value (conceptual)
{
  0xabc123...  (SignedData <PeerInfo>)   ;; signed by key 0xabc123...
  0xdef456...  (SignedData <PeerInfo>)   ;; signed by key 0xdef456...
  0x789012...  (SignedData <PeerInfo>)   ;; signed by key 0x789012...
}
```

The **key is the actual Ed25519 public key** (AccountKey, 32 bytes). This is critical: the key used to index the map is the same key used to verify the signature on the associated SignedData. This provides immediate verifiability — any node can confirm that a peer entry was genuinely produced by the claimed identity without any additional lookup.

### 3.2 PeerInfo Record

Each peer publishes a signed PeerInfo record containing its network metadata:

```clojure
;; PeerInfo (wrapped in SignedData, signed by the peer's Ed25519 key)
{:transports  #{"tcp://peer.convex.world:18888"
                "wss://peer.convex.world/ws"
                "https://peer.convex.world"}
 :timestamp   1708000000000       ;; monotonic, milliseconds
 :regions     #{:consensus :p2p :data :dlfs}  ;; lattice regions this peer participates in
 :version     "0.8.2"             ;; protocol version
 :metadata    {...}}              ;; optional additional metadata
```

### 3.3 Merge Function

The merge function for the P2P Lattice follows the standard lattice map merge pattern with merge context:

```
new_value = merge(context, existing, received)

  for each AccountKey k in union(keys(existing), keys(received)):
    if k only in existing:  keep existing[k]
    if k only in received:  validate and accept received[k]
    if k in both:           take the entry with the higher timestamp,
                            but ONLY if the signature is valid for key k
```

**Merge context** is used for:
- **Signature verification**: The merge only accepts a PeerInfo entry if its SignedData signature is valid for the AccountKey used as the map key. Invalid signatures are silently discarded.
- **Timestamp monotonicity**: A received entry replaces an existing one only if its timestamp is strictly greater. This prevents replay of old metadata.
- **Conditional acceptance**: Nodes may apply additional rules (e.g., rejecting entries from peers with zero stake, or peers not registered on-chain).

This design means:
- A peer can only update its own entry (only it possesses the private key to sign)
- Stale or forged entries are automatically rejected
- The map converges to the latest valid state for each peer under gossip
- No coordination is needed — the CRDT properties guarantee convergence

### 3.4 Kademlia-Style Routing

The P2P Lattice operates in a manner similar to Kademlia for routing and peer selection. Peers are addressed by their Ed25519 public key (interpreted as a 256-bit identifier), and XOR distance determines proximity in cryptographic space.

Each node maintains a **partial view** of the P2P Lattice — specifically, entries for peers that are "near" in XOR distance, plus a selection of more distant peers for logarithmic routing. This follows the Kademlia k-bucket model: nodes only need to store metadata for peers relatively near to them, making this a highly efficient and fault-tolerant decentralised service.

Routing provides O(log N) hop discovery of any peer on the network.

### 3.5 Efficiency

The P2P Lattice benefits from all the standard lattice efficiency mechanisms:

- **Delta transmission**: Only changed PeerInfo entries are transmitted during gossip, not the entire map
- **Structural sharing**: The underlying immutable persistent data structures share unchanged subtrees
- **Merge coalescing**: Multiple received updates can be merged locally before propagating a single combined update
- **Garbage collection**: Entries for peers that have been offline beyond a threshold can be pruned

## 4. Relationship to Other Lattice Regions

The P2P Lattice follows the same structural pattern as the Consensus Lattice (Belief = map of AccountKey → SignedData\<Order\>) and other lattice regions. All use AccountKey-indexed maps with SignedData, converge through gossip merges, and share the same CAD3 encoding and delta transmission. This means the networking layer treats all regions uniformly — the same gossip protocol propagates all lattice state, differing only in the merge function applied per region. See the [Convex White Paper](https://docs.convex.world/docs/overview/convex-whitepaper) for details on the Consensus Lattice specifically.

## 5. Transport Layer

### 5.1 Architecture

```
┌─────────────────────────────────────────┐
│       Lattice Regions                   │
│  (Consensus, P2P, Data, DLFS, Exec)    │
├─────────────────────────────────────────┤
│   Binary Protocol Messages (CAD3)       │
├─────────────────────────────────────────┤
│          Transport Adapter              │
├──────┬──────────┬──────────┬────────────┤
│ TCP  │ WebSocket│  HTTPS   │ Streamable │
│:18888│  (WSS)   │ req/resp │ HTTP (SSE) │
└──────┴──────────┴──────────┴────────────┘
```

All transports implement a unified connection interface:

```java
interface PeerConnection {
    CompletableFuture<Result> send(Message msg);
    void onReceive(Consumer<Message> handler);
    ConnectionState getState();
    void close();
    void addStateListener(Consumer<ConnectionState> listener);
    ConnectionType type();  // TCP, WSS, HTTP, STREAM
}
```

### 5.2 Transport Comparison

| Transport | Port | Use Case | Encrypted | Bidirectional | Persistent |
|---|---|---|---|---|---|
| Raw TCP | 18888 | Consensus sync, belief merge, validator gossip | No | Yes | Yes |
| WebSocket (WSS) | 443 | Browsers, AI agents, general clients | Yes (TLS) | Yes | Yes |
| HTTPS req/resp | 443 | Simple queries, transaction submission | Yes (TLS) | No (client-initiated) | No |
| Streamable HTTP (SSE) | 443 | Subscriptions, MCP transport | Yes (TLS) | Half-duplex (POST + SSE) | Yes |

### 5.3 Raw TCP (Port 18888)

Used for validator-to-validator consensus communication. Messages are length-prefixed binary frames carrying CAD3-encoded data.

Encryption is not required because consensus messages (beliefs, blocks) are **public data** and are **individually signed** by the originating peer's Ed25519 key via SignedData. Any tampered message fails signature verification and is dropped. Peer identities and stakes are public on-chain, so metadata privacy is not a concern for consensus traffic.

### 5.4 WebSocket (WSS)

Full-duplex binary channel over TLS. WSS is WebSocket over TLS — the existing TLS configuration for HTTPS covers WSS automatically. The existing binary protocol messages are carried directly as WebSocket binary frames.

This is the preferred transport for clients needing persistent connections — browsers, AI agents, wallets, and dApps. Runs on port 443 alongside HTTPS, making it firewall-friendly.

Java support is straightforward: if the peer already runs on Netty or Jetty for HTTP, adding a WebSocket handler is simply adding a route. Java 11+ `HttpClient` supports WebSocket client-side out of the box.

### 5.5 HTTPS Request/Response

Stateless HTTP endpoints for simple operations. Supports both CAD3 binary (`Content-Type: application/octet-stream`) and JSON (`application/json`) depending on the `Accept` header.

### 5.6 Streamable HTTP (SSE)

Follows the MCP Streamable HTTP pattern: client sends a POST request, and the response is either a single JSON-RPC response or upgrades to an SSE stream for server-initiated push. The client includes a session ID header (`Mcp-Session-Id`) for continuity.

This reuses the existing MCP transport pattern rather than inventing a new streaming protocol, and provides a universal fallback for clients that cannot use WebSocket.

## 6. Transport Advertisement

Peers advertise their available transports as a **set of URIs** within the PeerInfo record in the P2P Lattice. Individual API endpoints beneath each transport are standardised and not advertised separately.

### 6.1 URI Format

```
tcp://peer.convex.world:18888            Raw binary P2P
wss://peer.convex.world/ws               WebSocket binary
https://peer.convex.world                HTTPS (REST + SSE + MCP)
```

For peer-to-peer addressing by public key:

```
tcp://<ed25519-pubkey-hex>@peer.convex.world:18888
```

### 6.2 Transport Selection

Clients select the best available transport from the peer's advertised set:

- Validators prefer TCP for consensus traffic (lowest overhead)
- Browsers use WSS (native WebSocket support)
- Simple API callers use HTTPS
- AI agents and MCP clients use WSS or Streamable HTTP

### 6.3 On-Chain Peer Registration

Peers are registered on-chain with their AccountKey (Ed25519 public key) and stake. The on-chain peer record is the authoritative source of peer identity. The P2P Lattice provides fast off-chain propagation of transport metadata, with on-chain registration as the root of trust.

```clojure
;; On-chain peer record (existing)
{:key        0xabc123...         ;; Ed25519 AccountKey
 :stake      1000000000000       ;; staked amount in copper
 :url        "https://peer.convex.world"}

;; P2P Lattice extends this with richer off-chain metadata
;; propagated via gossip with SignedData verification
```

## 7. Standardised Endpoints

All HTTPS/WSS endpoints follow a standard structure under the advertised base URI:

```
https://peer.convex.world
├── /api/v1
│   ├── /transact          POST   Submit signed transaction
│   ├── /prepare           POST   Prepare transaction, get hash
│   ├── /query             POST   Read-only query
│   ├── /faucet            POST   Request testnet funds
│   ├── /account/{addr}    GET    Account info
│   ├── /status            GET    Peer status and network info
│   └── /block/{hash}      GET    Block data
│
├── /ws                     WSS    Persistent binary protocol
│
├── /stream/v1
│   ├── /subscribe          POST   Subscribe to state changes (SSE)
│   └── /gossip             POST   Peer gossip channel (SSE)
│
├── /mcp                    SSE    MCP transport (existing)
│
└── /peer/v1
    ├── /hello              POST   Peer handshake (signed HELLO)
    ├── /peers              GET    Known peer list (P2P Lattice subset)
    └── /belief             POST   Submit/request belief merge
```

## 8. Authentication

### 8.1 Ed25519 Challenge/Response (WSS, HTTPS Sessions)

Identity is established via Ed25519 challenge/response. The peer verifies that the connecting party possesses the private key corresponding to a claimed AccountKey:

```
Client                              Peer
  │                                   │
  ├── HELLO {AccountKey, transports} ──→
  │                                   │
  ←── CHALLENGE {nonce (32 bytes)} ────┤
  │                                   │
  ├── RESPONSE {sig(nonce ‖            │
  │     peer_AccountKey ‖ timestamp)} ─→
  │                                   │
  ←── AUTHENTICATED ───────────────────┤
```

The signed payload includes the peer's AccountKey to prevent MITM forwarding, and a timestamp to prevent replay. The nonce must be server-generated and random.

### 8.2 Mutual Authentication

For peer-to-peer connections where both sides verify identity:

```
Client                              Peer
  │                                   │
  ├── HELLO {key_c, nonce_c} ──────────→
  │                                   │
  ←── HELLO {key_p, nonce_p,           │
  │          sig_p(nonce_c)} ──────────┤
  │                                   │
  ├── AUTH {sig_c(nonce_p)} ───────────→
  │                                   │
  ←── AUTHENTICATED ───────────────────┤
```

Two round trips for mutual authentication, one for client-only.

### 8.3 Raw TCP (Consensus)

No per-connection authentication is needed. Every consensus message (belief, block order) is wrapped in **SignedData** — signed by the peer's Ed25519 key with the AccountKey embedded. Any recipient validates the signature against the AccountKey before accepting the merge. Invalid signatures are dropped.

### 8.4 Re-authentication on Reconnect

On reconnect, the challenge/response is re-run. It is cheap (one Ed25519 signature + one verify). The peer can then restore session state associated with the AccountKey (subscriptions, pending responses).

## 9. Connection Management

### 9.1 Connection Lifecycle

```
CONNECTING → CONNECTED → DISCONNECTED
                ↑              │
                └── RECONNECTING ←┘
                        │
                        → FAILED (max retries)
```

### 9.2 Client API

```java
// Simple connection
Connection conn = Convex.connect("wss://peer.convex.world/ws");

// With configuration
Connection conn = Convex.connect("wss://peer.convex.world/ws",
    ConnectionConfig.builder()
        .reconnect(true)              // default: true
        .maxRetries(5)                // default: unlimited
        .backoff(1000, 30000)         // initial ms, max ms (exponential)
        .timeout(5000)                // connect timeout ms
        .onDisconnect(c -> log)
        .onReconnect(c -> resubscribe)
        .build());
```

`Convex.connect(uri)` currently supports TCP and HTTPS URIs. This design extends it to also accept `wss://` URIs, selecting the appropriate transport adapter internally.

### 9.3 Reconnect Behaviour

Automatic reconnection with exponential backoff is the default:

- **Transactions**: Fail-fast. The caller must know the message was not delivered so they can handle sequence number management.
- **Queries**: Fail-fast. Queries are idempotent and the caller can re-issue.
- **Subscriptions**: Auto-resubscribe on reconnect. Session continuity allows the peer to restore subscription state without the client re-registering.

The `CompletableFuture<Result>` return from `send()` naturally handles the async case — it completes when the response arrives, or completes exceptionally if the connection is in FAILED state.

### 9.4 Transport Failover

If a peer advertises multiple transports in the P2P Lattice, `Convex.connect()` can attempt them in preference order on failure: WSS → HTTPS → TCP. On reconnect, if the current transport repeatedly fails, the next transport is tried.

## 10. Protocol Messages

All messages are CAD3-encoded binary, regardless of transport.

| Message | Purpose | Transports |
|---|---|---|
| `HELLO` | Signed handshake (AccountKey + transports + timestamp) | All |
| `CHALLENGE` / `RESPONSE` | Ed25519 identity verification | WSS, HTTPS |
| `BELIEF` | SignedData\<Belief\> — consensus belief propagation | TCP, WSS |
| `QUERY` | Read-only CVM query | All |
| `TRANSACT` | Submit SignedData\<Transaction\> | All |
| `FIND_NODE` | Request peers near a target AccountKey (Kademlia) | TCP, WSS |
| `PING` / `PONG` | Liveness check | TCP, WSS |
| `GOSSIP` | P2P Lattice delta (peer metadata updates) | TCP, WSS |
| `SUBSCRIBE` / `NOTIFY` | State change subscription | WSS, SSE |
| `ACQUIRE` | Request content-addressable data (Data Lattice) | TCP, WSS |

## 11. Security Model

| Transport | Confidentiality | Integrity | Authentication |
|---|---|---|---|
| TCP :18888 | None (public data) | SignedData per message | AccountKey in SignedData |
| WSS :443 | TLS | TLS + SignedData | Challenge/response (AccountKey) |
| HTTPS :443 | TLS | TLS + SignedData | Challenge/response or API key |
| SSE :443 | TLS | TLS + SignedData | Session ID + challenge/response |

The security model leverages the fact that **SignedData is fundamental to all lattice operations**. Every belief, every peer metadata update, every transaction is wrapped in SignedData with the originator's AccountKey. This provides end-to-end integrity and authentication independent of transport security.

Raw TCP carries only public consensus data (beliefs, peer metadata) which are individually signed. TLS provides confidentiality for client-facing transports where transaction details or query results may be sensitive.

## 12. Peer Management and Reputation

### 12.1 Peer Lifecycle

1. **Registration**: Peer registers on-chain with AccountKey and stake
2. **Advertisement**: Peer publishes SignedData\<PeerInfo\> to the P2P Lattice
3. **Discovery**: Other peers discover via Kademlia routing and gossip
4. **Connection**: Peers establish connections using advertised transports
5. **Participation**: Peer joins lattice regions (consensus, data, etc.)
6. **Departure**: Peer's PeerInfo ages out; stake can be withdrawn on-chain

### 12.2 Reputation Metrics

Peers track reliability per connection:

- **Message delivery ratio**: forwarded vs. acknowledged messages
- **Latency**: rolling average round-trip time
- **Liveness**: exponential decay on last-seen — peers that go silent are deprioritised
- **Stake weight**: on-chain stake influences routing priority for consensus paths

Peers are evicted from Kademlia k-buckets only when a better candidate (closer in XOR distance, higher reliability) becomes available. Stake-weighted reputation ensures active validators maintain strong connectivity.

### 12.3 Conditional Acceptance

Following the lattice merge context model, nodes apply conditional acceptance rules during P2P Lattice merges:

- Reject entries with invalid signatures (always)
- Reject entries with non-monotonic timestamps (always)
- Optionally reject entries from peers with zero on-chain stake
- Optionally reject entries from peers not registered on-chain
- Optionally apply rate limits to prevent metadata spam

These rules are enforced locally by each node. Invalid entries are simply discarded — they cannot propagate through the network because every honest node independently applies the same validation.

## 13. Lattice Gossip Protocol

### 13.1 Gossip Cycle

The gossip protocol for lattice regions follows a simple pattern:

1. **Merge locally**: Coalesce all recently received lattice updates
2. **Compute delta**: Determine what has changed since the last transmission to each connected peer (using structural sharing and fast comparison)
3. **Transmit delta**: Send only the changed portions (CAD3 delta encoding)
4. **Receive and merge**: Accept deltas from peers, apply merge function with context

This cycle runs continuously. Merge coalescing naturally reduces traffic — a node receiving N updates produces at most one outgoing delta per peer.

### 13.2 Cross-Region Gossip

A single gossip message can carry deltas for multiple lattice regions simultaneously. This is efficient because the top-level lattice structure is itself a map of region identifiers to region values:

```clojure
;; Gossip message (conceptual)
{:p2p       <delta of P2P Lattice>
 :consensus <delta of Consensus Belief>
 :data      <delta of Data Lattice acquisitions>}
```

Peers only include deltas for regions they and the recipient both participate in.

## 14. Summary

The Convex peer network is not a separate system bolted onto the lattice — it **is** a lattice region. The P2P Lattice uses the same algebraic foundations (join-semilattices), the same data structures (immutable persistent Merkle trees with CAD3 encoding), the same security model (SignedData with Ed25519 AccountKeys), and the same propagation mechanisms (gossip with delta transmission and merge coalescing) as every other part of the Convex ecosystem.

This uniformity means:
- One protocol implementation serves all lattice regions
- One gossip mechanism propagates all state
- One security model (SignedData + AccountKey) authenticates everything
- One encoding format (CAD3) represents everything on the wire and in storage

The transport layer (TCP, WSS, HTTPS, SSE) is a thin adapter beneath this unified lattice protocol, chosen based on the use case: raw performance for validators, firewall-friendly encrypted channels for clients, and standard HTTP for simple integrations.
