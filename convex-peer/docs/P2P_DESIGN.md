# Convex Peer Network Design — PROPOSAL

> **Status**: Design proposal. Sections marked **[EXISTS]** describe current implementation; sections marked **[PROPOSED]** describe new work. Items marked **[DECISION]** require further discussion.

## 1. Overview

This document proposes the design for the Convex peer-to-peer network layer. The network enables peers to discover each other, establish authenticated connections over multiple transports, synchronise consensus state, and serve client requests.

The design builds on the existing `NodeServer` infrastructure, which already implements lattice value propagation, path-based merges, and delta gossip. The proposal extends this with:

- **Consensus beliefs as a lattice region** — Belief merge (CPoS) becomes part of the unified lattice, not a separate subsystem
- **P2P peer discovery lattice** — signed peer metadata with multiple routing strategies
- **Multi-transport connections** — TCP, WebSocket, HTTPS, SSE with transport advertisement
- **Reconnection and failover** — automatic reconnect with exponential backoff

The core design principle is **separation of the message protocol from the transport layer**. All transports carry the same CAD3-encoded binary protocol messages — the transport is simply the delivery mechanism.

## 2. Current Architecture [EXISTS]

### 2.1 Lattice ROOT

The global base lattice is defined by `Lattice.ROOT` (`KeyedLattice`). See [LATTICE_REGIONS.md](../../convex-core/docs/LATTICE_REGIONS.md) for the canonical region listing, types, and paths.

Each region uses the same algebraic foundations: join-semilattices, SignedData verification, delta transmission, and structural sharing via immutable persistent data structures with CAD3 encoding.

### 2.2 NodeServer

`NodeServer<V>` is the main implementation for serving and propagating lattice state. It handles:

- **LATTICE_VALUE** (`[:LV [*path*] value]`) — Receive a value at a path, validate via sub-lattice, merge atomically, broadcast delta to propagators
- **LATTICE_QUERY** (`[:LQ id [*path*]]`) — Respond with current value at a lattice path
- **DATA_REQUEST** (`[:DR id hash1 hash2 ...]`) — Serve content-addressable data from the store
- **PING** — Liveness check

Key features:
- **Automatic missing data recovery** — on `MissingDataException` during merge, acquires data from peers and retries
- **Copy-on-write cursor** — atomic updates via `cursor.updateAndGet()`
- **LatticeContext** — carries signing keys through the lattice hierarchy for `OwnerLattice`/`SignedLattice` verification
- **LatticePropagator** — manages gossip to connected peers (primary propagator at index 0)

### 2.3 Consensus (Server + BeliefPropagator)

The consensus layer currently operates **outside** the lattice region system:

- `Server` coordinates all peer components: `ConnectionManager`, `BeliefPropagator`, `TransactionHandler`, `QueryHandler`, `CVMExecutor`
- `BeliefPropagator` queues incoming BELIEF messages, merges via `BeliefMerge`, broadcasts deltas
- `ConnectionManager` maintains outbound `Convex` client connections, uses stake-weighted random selection
- Belief = `Index<AccountKey, SignedData<Order>>` — already a lattice value with merge semantics

### 2.4 Protocol Messages [EXISTS]

Current `MessageType` enum (16 types):

| Code | Type | Purpose |
|------|------|---------|
| 1 | CHALLENGE | Ed25519 auth challenge `[token, networkId, toPeer]` |
| 2 | RESPONSE | Auth response `[token, networkId, fromPeer, challengeHash]` |
| 3 | DATA | Content-addressable data relay |
| 4 | COMMAND | Control command to peer (trusted senders only) |
| 5 | DATA_REQUEST | Request missing data by hash |
| 6 | QUERY | Read-only CVM query `[:Q id form address?]` |
| 7 | TRANSACT | Submit signed transaction `[:TX id signed-data]` |
| 8 | RESULT | Response to query/transact/command |
| 9 | BELIEF | Consensus belief propagation (Belief or SignedData\<Order\>) |
| 10 | REQUEST_BELIEF | Poll for latest belief |
| 11 | GOODBYE | Connection shutdown `[:BYE message?]` |
| 12 | STATUS | Request peer status |
| 13 | UNKNOWN | Unrecognised message type |
| 14 | LATTICE_VALUE | Lattice delta `[:LV [*path*] value]` |
| 15 | LATTICE_QUERY | Query lattice value `[:LQ id [*path*]]` |
| 16 | PING | Connectivity check `[:PING id]` |

### 2.5 Transport [EXISTS]

Currently only **raw TCP** (port 18888) via Netty:
- `AConnection` — abstract base class with `sendMessage()`/`close()`
- `NettyConnection` — TCP implementation with outbound queue
- `NettyServer`/`NIOServer` — inbound connection handlers
- All messages are length-prefixed CAD3-encoded binary frames

See [MESSAGING.md](MESSAGING.md) for the connection model, paired local
channels, and client architecture.

The REST API (`convex-restapi`) runs separately via Javalin on a configurable HTTP port, serving `/api/v1/*` endpoints. MCP transport uses SSE over HTTP.

## 3. Consensus as a Lattice Region [PROPOSED]

### 3.1 Motivation

Belief already has lattice merge semantics (`Index<AccountKey, SignedData<Order>>`, CPoS merge). Currently it's propagated by `BeliefPropagator` as a separate subsystem with its own message types (BELIEF, REQUEST_BELIEF) and broadcast logic. Moving it into the lattice region system means:

- One propagation mechanism for all lattice state (including consensus)
- Belief merges use the same path-based LATTICE_VALUE protocol as all other regions
- `NodeServer` handles missing data recovery, delta encoding, and fan-out uniformly

### 3.2 Lattice Path

Consensus beliefs live at:

```
[:convex <genesis-hash> :peers]
```

Where `<genesis-hash>` is the network's genesis state hash (32-byte `ABlob`), scoping beliefs to a specific Convex network. This allows a single node to participate in multiple networks simultaneously.

The value at this path is the Belief: `Index<AccountKey, SignedData<Order>>`.

Navigating deeper:

```
[:convex <genesis-hash> :peers <peer-AccountKey>]
```

Returns the `SignedData<Order>` for a specific peer.

### 3.3 Merge Semantics

The merge function at the `:peers` level is exactly the existing CPoS belief merge — `BeliefMerge.merge()`. This includes:

- Signature verification on each `SignedData<Order>`
- Timestamp ordering within Orders
- Block proposal accumulation
- Consensus point advancement

The lattice type wrapping this would be a `BeliefLattice` implementing `ALattice<Index<AccountKey, SignedData<Order>>>`, delegating to the existing `BeliefMerge` logic.

### 3.4 Transition Strategy

The migration to lattice-based consensus propagation follows a **gradual** approach:

1. **Phase 1 — P2P lattice first**: Implement the `[:p2p :nodes]` region and get lattice-based peer discovery working. This exercises the `NodeServer` propagation path for a new region without touching consensus. Existing BELIEF messages and `BeliefPropagator` remain unchanged.

2. **Phase 2 — BeliefLattice type**: Implement `BeliefLattice` wrapping `BeliefMerge`, register it at `[:convex <genesis-hash> :peers]`. Add support for beliefs arriving as LATTICE_VALUE at this path alongside the existing BELIEF message type.

3. **Phase 3 — Dual path**: Both BELIEF and LATTICE_VALUE carry consensus updates. Peers accept either. `BeliefPropagator` retains its timing loop (30ms accumulation, 300ms rebroadcast, block generation triggers) but can optionally emit LATTICE_VALUE messages.

4. **Phase 4 — Deprecate BELIEF**: Once the LATTICE_VALUE path is proven, deprecate the dedicated BELIEF message type. `BeliefPropagator` remains as a consensus-specific timing wrapper but speaks the standard lattice protocol.

This approach avoids disrupting the consensus-critical path while progressively unifying the propagation mechanism.

### 3.5 Network Identity via Genesis Hash

The genesis hash uniquely identifies a Convex network. Using it in the lattice path means:

- A node can serve beliefs for multiple networks (e.g., mainnet + testnet)
- Peers automatically scope their belief merges to the correct network
- No confusion between networks — genesis hash mismatch means different lattice paths

The `:convex` keyword at the root level is a namespace for Convex-native protocol state (consensus, peer status, governance). Application-level regions (`:data`, `:fs`, `:kv`, `:queue`) remain at their existing root-level paths.

## 4. P2P Discovery Lattice [PROPOSED]

### 4.1 Lattice Paths

P2P state lives under the `:p2p` region with two sub-regions:

```
[:p2p :nodes]  → OwnerLattice<NodeInfo>   Known peers and their metadata
[:p2p :kad]    → KadLattice               Kademlia routing table
```

Both follow standard lattice merge semantics with SignedData verification.

### 4.2 Node Registry: `[:p2p :nodes]`

The node registry is a map of `AccountKey → SignedData<NodeInfo>`:

```clojure
;; Value at [:p2p :nodes] (conceptual)
{0xabc123... → SignedData<NodeInfo>   ;; signed by 0xabc123...
 0xdef456... → SignedData<NodeInfo>   ;; signed by 0xdef456...}
```

This uses `OwnerLattice` — the same pattern as `:kv` and `:fs`. The AccountKey used as the map key must match the signer of the `SignedData`, providing immediate verifiability.

#### NodeInfo Record

```clojure
;; NodeInfo (wrapped in SignedData)
{:transports  #{"tcp://peer.convex.world:18888"
                "wss://peer.convex.world/ws"
                "https://peer.convex.world"}
 :timestamp   1708000000000       ;; monotonic, milliseconds
 :regions     #{:convex :p2p :data :fs :kv}  ;; lattice regions this peer serves
 :version     "0.8.3"             ;; protocol version
 :metadata    {...}}              ;; optional additional metadata
```

#### Merge Function

Standard `OwnerLattice` merge with additional NodeInfo-specific rules:

- **Signature verification** via `OwnerLattice` — signer must match owner key
- **Timestamp monotonicity** — newer NodeInfo replaces older (LWW per owner)
- **Conditional acceptance** via `LatticeContext` — nodes may reject entries from peers with zero on-chain stake, unregistered peers, etc.

#### Relationship to On-Chain PeerStatus

On-chain `PeerStatus` (in global consensus state) is the **single source of truth** for peer identity: AccountKey, stake, controller address, delegated stakes, balance, and metadata (including a `:url` hostname entry). This is the authoritative peer registry — if a peer isn't registered on-chain with stake, it isn't a peer.

The P2P node registry at `[:p2p :nodes]` provides **supplementary off-chain metadata** for operational convenience: multiple transport URIs, supported lattice regions, protocol version, and capabilities. This data propagates faster than on-chain updates (gossip vs. consensus finality) but has weaker guarantees (no finality, latest-timestamp-wins). It is always subordinate to the on-chain record.

**Bootstrap path**: A new node reads the on-chain peer list (PeerStatus records with hostnames), connects to a peer via the on-chain `:url`, then pulls the P2P node registry to discover richer transport metadata. The on-chain URL is always the fallback when P2P metadata is unavailable.

**Validation**: Nodes should validate P2P entries against on-chain state — reject NodeInfo from AccountKeys not registered on-chain, or from peers with zero stake. The on-chain PeerStatus is the trust anchor; the P2P lattice is a convenience cache.

### 4.3 Kademlia Routing [FUTURE]

Kademlia provides O(log N) lookup of any peer by AccountKey, using XOR distance. The `Kademlia` utility class already provides `proximity()` and `distance()` functions. `KadLattice` exists as a stub.

This is planned for **future implementation** when the network scales beyond what the node registry and on-chain peer list can efficiently serve. For current network sizes (up to hundreds of peers), strategies 1-3 in §4.4 are sufficient.

When implemented, Kademlia routing state would be **node-local** (not propagated) since each node's k-bucket view is inherently specific to its own key position. It would live under `:local` or as a non-propagated local data structure.

### 4.4 Peer Discovery Strategies

A node uses multiple strategies to discover and maintain connections, in order:

1. **Configured peers** — Explicitly configured connection endpoints (bootstrap nodes, operator preferences). These are tried first and maintained persistently.

2. **On-chain peer registry** — `PeerStatus` entries in consensus state provide hostname/URL for staked peers. Used for bootstrapping and as fallback. `ConnectionManager` already uses stake-weighted random selection from this registry.

3. **P2P node registry** (`[:p2p :nodes]`) — Richer transport metadata propagated via gossip. Provides multi-protocol endpoints and faster updates than on-chain.

4. **Kademlia lookup** [FUTURE] — For discovering specific peers by AccountKey when direct routing is needed. O(log N) hops. Not needed at current network scale; planned for future growth.

### 4.5 Selective Attention

Nodes only propagate lattice regions they participate in. A lightweight data node need not propagate consensus beliefs. A consensus validator need not propagate DLFS file systems. The P2P node registry advertises which regions each peer serves, enabling efficient gossip targeting.

This is already natural in the `NodeServer` model — propagators only transmit deltas for paths that have changed, and nodes only merge values for lattice types they have registered.

## 5. Transport Layer [PROPOSED]

### 5.1 Architecture

```
┌─────────────────────────────────────────┐
│            Lattice Regions              │
│  (Consensus, P2P, Data, FS, KV, ...)   │
├─────────────────────────────────────────┤
│      NodeServer (merge + propagate)     │
├─────────────────────────────────────────┤
│   Binary Protocol Messages (CAD3)       │
├─────────────────────────────────────────┤
│          Transport Adapter              │
├──────┬──────────┬──────────┬────────────┤
│ TCP  │ WebSocket│  HTTPS   │ Streamable │
│:18888│  (WSS)   │ req/resp │ HTTP (SSE) │
└──────┴──────────┴──────────┴────────────┘
```

`NodeServer` sits at the centre, managing lattice state and dispatching to/from transport adapters. This is the key difference from the current architecture where `Server` manages consensus separately from `NodeServer`.

### 5.2 Transport Comparison

| Transport | Port | Use Case | Encrypted | Bidirectional | Persistent |
|---|---|---|---|---|---|
| Raw TCP | 18888 | Validator-to-validator: belief gossip, lattice sync | No (public data) | Yes | Yes |
| WebSocket (WSS) | 443 | Browsers, AI agents, general clients | Yes (TLS) | Yes | Yes |
| HTTPS req/resp | 443 | Simple queries, transaction submission | Yes (TLS) | No (client-initiated) | No |
| Streamable HTTP (SSE) | 443 | Subscriptions, MCP transport | Yes (TLS) | Half-duplex (POST + SSE) | Yes |

### 5.3 Raw TCP (Port 18888) [EXISTS]

Validator-to-validator communication via Netty. Messages are length-prefixed CAD3-encoded binary frames.

Encryption is not required because:
- Consensus messages (beliefs, orders, blocks) are **individually signed** via SignedData — tampered messages fail verification
- Peer identities and stakes are public on-chain
- Lattice values carry their own integrity via Merkle hashing

Raw TCP carries **only** peer-to-peer lattice traffic. Client-facing traffic (queries, transactions with potentially sensitive data) should use TLS-protected transports.

### 5.4 WebSocket (WSS) [PROPOSED]

Full-duplex binary channel over TLS. The existing CAD3 binary protocol messages are carried directly as WebSocket binary frames.

Preferred transport for clients needing persistent connections — browsers, AI agents, wallets, dApps. Runs on port 443 alongside HTTPS, making it firewall-friendly.

Since the peer already runs Netty, adding a WebSocket handler is adding a route to the existing pipeline.

### 5.5 HTTPS Request/Response [EXISTS — partial]

The REST API (`convex-restapi`, Javalin) already serves:

```
POST /api/v1/transact              Submit signed transaction
POST /api/v1/transaction/prepare   Prepare transaction, get hash
POST /api/v1/transaction/submit    Submit prepared transaction
POST /api/v1/query                 Read-only CVM query
POST /api/v1/faucet                Request testnet funds
POST /api/v1/createAccount         Create new account
GET  /api/v1/accounts/{addr}       Account info
GET  /api/v1/status                Peer status
GET  /api/v1/blocks/{num}          Block data
GET  /api/v1/data/{hash}           Content-addressable data
POST /api/v1/data/encode           Encode to CAD3
POST /api/v1/data/decode           Decode from CAD3
```

These endpoints can also support CAD3 binary (`Content-Type: application/octet-stream`) alongside JSON for lower overhead.

### 5.6 Streamable HTTP (SSE) [EXISTS — MCP only]

The MCP transport already implements SSE over HTTP for AI agent integration (`POST /mcp` with SSE response stream). This pattern could be generalised for lattice subscriptions.

### 5.7 Server Architecture

Two server frameworks, each handling what it does best:

- **Netty** — Binary protocol over TCP (port 18888) and WSS (port 443). High-performance peer-to-peer lattice traffic: beliefs, lattice deltas, data sync. Length-prefixed CAD3 frames.
- **Javalin** — HTTP-based APIs: REST endpoints (`/api/v1/*`), MCP transport (`/mcp`), SSE subscriptions. Web/API-friendly with JSON support, middleware, CORS, etc.

Both connect to the same `NodeServer` for lattice operations and the same `Server` for consensus/transaction handling.

## 6. Transport Advertisement [PROPOSED]

### 6.1 URI Format

Peers advertise available transports as a set of URIs in NodeInfo:

```
tcp://peer.convex.world:18888            Raw binary (peer-to-peer)
wss://peer.convex.world/ws               WebSocket binary (clients)
https://peer.convex.world                HTTPS REST + MCP
```

Public key addressing for peer-to-peer:

```
tcp://0xabc123...@peer.convex.world:18888
```

### 6.2 Transport Selection

Clients select the best available transport from the peer's advertised set:

- **Validators** prefer TCP for consensus (lowest overhead)
- **Browsers** use WSS (native WebSocket support)
- **Simple API callers** use HTTPS
- **AI agents / MCP clients** use WSS or Streamable HTTP

### 6.3 On-Chain as Source of Truth

On-chain `PeerStatus` is the authoritative peer registry. Its metadata includes a `:url` entry (hostname) which is the primary and always-available connection point. The P2P node registry provides supplementary transport metadata.

**Bootstrap flow**:

1. New node reads on-chain peer list — `PeerStatus` records with hostnames and stakes
2. Connects to staked peers via on-chain `:url` (TCP or HTTPS)
3. Optionally pulls P2P node registry (`[:p2p :nodes]`) for richer transport metadata
4. Establishes preferred connections using advertised URIs where available, falling back to on-chain hostname

## 7. Authentication [EXISTS + PROPOSED]

### 7.1 Raw TCP (Peer-to-Peer) [EXISTS]

No per-connection authentication needed. Every lattice value and consensus message is wrapped in **SignedData** — signed by the originating peer's Ed25519 key. Any recipient validates the signature against the AccountKey before accepting the merge. Invalid signatures are silently dropped.

This is fundamental: SignedData provides **end-to-end** authentication independent of transport.

### 7.2 Ed25519 Challenge/Response [EXISTS]

`CHALLENGE` (type 1) and `RESPONSE` (type 2) messages already exist for identity verification. The peer verifies that the connecting party possesses the private key for a claimed AccountKey:

```
Client                              Peer
  │                                   │
  ├── CHALLENGE request ──────────────→
  │                                   │
  ←── CHALLENGE [token, networkId,    │
  │               toPeer] ────────────┤
  │                                   │
  ├── RESPONSE [token, networkId,     │
  │            fromPeer, sig] ────────→
  │                                   │
  ←── AUTHENTICATED ──────────────────┤
```

The challenge includes `networkId` (genesis hash) to prevent cross-network replay, and the token is server-generated random bytes.

### 7.3 Re-authentication on Reconnect

On reconnect, challenge/response is re-run (cheap: one Ed25519 sign + verify). The peer can restore session state (subscriptions, pending responses) keyed by AccountKey.

## 8. Connection Management [PROPOSED]

### 8.1 Connection Lifecycle

```
CONNECTING → CONNECTED → DISCONNECTED
                ↑              │
                └── RECONNECTING ←┘
                        │
                        → FAILED (max retries)
```

### 8.2 Current Implementation [EXISTS]

`ConnectionManager` maintains a `HashMap<AccountKey, Convex>` of outbound connections. It:
- Uses stake-weighted random selection from on-chain peer registry
- Drops peers below minimum stake threshold
- Maintains configurable target connection count (`:outgoing-connections` config)
- Polls random peers for latest belief
- Broadcasts messages to all live connections (shuffled order)

### 8.3 Reconnection [PROPOSED]

Automatic reconnection with exponential backoff:

- **Initial delay**: 1 second
- **Max delay**: 30 seconds (exponential growth with jitter)
- **Max retries**: Configurable (default: unlimited for configured peers, limited for discovered peers)

Per message type:
- **Transactions**: Fail-fast on disconnect. Caller must handle sequence number management.
- **Queries**: Fail-fast. Idempotent — caller can re-issue.
- **Lattice subscriptions**: Auto-resubscribe on reconnect.

### 8.4 Transport Failover [PROPOSED]

If a peer advertises multiple transports in `[:p2p :nodes]`, `Convex.connect()` can attempt them in preference order on failure:

1. WSS (full-duplex, TLS)
2. HTTPS (stateless, TLS)
3. TCP (raw binary, no TLS)

On reconnect, if the current transport repeatedly fails, the next is tried.

## 9. NodeServer as P2P Core [PROPOSED]

### 9.1 Architecture

`NodeServer` becomes the unified core for all lattice propagation, including consensus:

```
NodeServer (lattice merge + propagate)
├── LatticePropagator[0] — primary (peers, gossip)
├── LatticePropagator[1..N] — additional propagators
├── BeliefPropagator — consensus-specific timing (gradual migration)
│   └── Currently: BELIEF messages
│   └── Future: LATTICE_VALUE at [:convex <genesis> :peers]
├── ConnectionManager — outbound peer connections
│   └── Manages Convex clients, stake-weighted selection from on-chain PeerStatus
└── Transport
    ├── NettyServer (TCP :18888)           [EXISTS] — peer binary protocol
    ├── NettyServer (WSS :443)             [PROPOSED] — client binary protocol
    └── Javalin (HTTPS + MCP)              [EXISTS] — web/API/MCP
```

### 9.2 Message Routing

Messages are currently split between `NodeServer` (lattice operations) and `Server` (consensus and client-facing). As more message types migrate to the lattice protocol, `NodeServer` will handle an increasing share.

**NodeServer** handles lattice messages directly:

| Message | Handler | Notes |
|---------|---------|-------|
| LATTICE_VALUE | `processLatticeValue()` → `cursor.path(path).merge(value)` | Navigate to target, merge via cursor |
| LATTICE_QUERY | `processLatticeQuery()` | Respond with value at path |
| DATA_REQUEST | `processDataRequest()` | Serve content-addressable data |
| PING | `processPing()` | Respond with RESULT |

**Server** handles consensus and client-facing messages:

| Message | Handler | Notes |
|---------|---------|-------|
| BELIEF | `BeliefPropagator.queueBelief()` | **Transition**: eventually becomes LATTICE_VALUE at consensus path |
| QUERY | `QueryHandler` | CVM query execution |
| TRANSACT | `TransactionHandler` | Transaction submission |
| CHALLENGE/RESPONSE | `ConnectionManager` | Authentication |
| STATUS | `Server` | Peer status |

### 9.3 Gossip via LatticePropagator

`LatticePropagator` (already exists) handles the gossip cycle:

1. **Receive merge** — `NodeServer` merges incoming LATTICE_VALUE, updates cursor
2. **Detect delta** — propagator computes what changed since last transmission to each peer
3. **Transmit delta** — send LATTICE_VALUE messages with only changed sub-trees
4. **Coalesce** — multiple incoming merges produce at most one outgoing delta per peer

This already works for `:data`, `:fs`, `:kv`, `:queue`. Extending it to `:convex` (consensus) and `:p2p` is adding lattice types, not new propagation mechanisms.

## 10. Security Model

### 10.1 End-to-End via SignedData [EXISTS]

SignedData is fundamental to all lattice operations. Every belief, every peer metadata update, every transaction is wrapped in SignedData with the originator's AccountKey. This provides integrity and authentication **independent** of transport security.

### 10.2 Per-Transport Security

| Transport | Confidentiality | Integrity | Authentication |
|---|---|---|---|
| TCP :18888 | None | SignedData per value | AccountKey in SignedData |
| WSS :443 | TLS | TLS + SignedData | Challenge/response |
| HTTPS :443 | TLS | TLS + SignedData | Challenge/response or bearer token |
| SSE :443 | TLS | TLS + SignedData | Session ID + challenge/response |

Raw TCP carries only peer-to-peer lattice traffic (beliefs, peer metadata, data sync) which is individually signed and public. TLS-protected transports are used for client-facing traffic where query results or transaction details may be sensitive.

### 10.3 Conditional Acceptance via LatticeContext [EXISTS]

Nodes apply acceptance rules during lattice merges via `LatticeContext`:

- **Always**: reject invalid signatures (enforced by `OwnerLattice`/`SignedLattice`)
- **Always**: reject non-monotonic timestamps (enforced by LWW merge)
- **Optional**: reject entries from peers with zero on-chain stake
- **Optional**: reject entries from unregistered peers
- **Optional**: rate limiting to prevent metadata spam

These rules are enforced locally. Invalid entries are silently discarded and cannot propagate through the network because every honest node independently validates.

## 11. Peer Lifecycle

1. **Registration**: Peer registers on-chain with AccountKey and stake
2. **Bootstrap**: Connects to configured peers or on-chain hostnames
3. **Advertisement**: Publishes `SignedData<NodeInfo>` to `[:p2p :nodes]`
4. **Discovery**: Discovered by other peers via P2P gossip (and optionally Kademlia)
5. **Connection**: Peers establish connections using advertised transport URIs
6. **Participation**: Peer joins lattice regions (consensus, data, etc.) via selective attention
7. **Departure**: NodeInfo ages out; peer can withdraw stake on-chain

## 12. Lattice Path Summary

Base regions are documented in [LATTICE_REGIONS.md](../../convex-core/docs/LATTICE_REGIONS.md). This document proposes extending the root with:

```
Lattice.ROOT (KeyedLattice)
│
├── ... base regions (see LATTICE_REGIONS.md)
│
└── :convex                                                [Phase 2]
    └── <genesis-hash>
        └── :peers → BeliefLattice (CPoS merge)
            └── <AccountKey> → SignedData<Order>
```

## 13. Design Decisions

### 13.1 Belief Propagation Timing — Gradual Migration (DECIDED)

`BeliefPropagator` retains its consensus-specific timing loop (30ms accumulation, 300ms rebroadcast, block generation triggers) throughout the migration. The lattice propagator is optimised for eventual consistency; consensus requires tighter timing. The gradual migration (§3.4) keeps BELIEF messages working while progressively adding LATTICE_VALUE support.

### 13.2 KadLattice — Future Work (DECIDED)

Kademlia routing is deferred until network scale demands it. Current peer discovery via on-chain PeerStatus + P2P node registry + configured peers is sufficient for hundreds of peers. When implemented, Kademlia state will be node-local (not propagated) since each node's k-bucket view is specific to its own key position.

### 13.3 Server Architecture — Netty + Javalin (DECIDED)

Netty handles binary protocol (TCP + WSS): high-performance peer-to-peer lattice traffic. Javalin handles HTTP-based APIs (REST + MCP + SSE): web-friendly with JSON, middleware, CORS. Both frameworks connect to the same `NodeServer` and `Server` instances.

### 13.4 PeerStatus as Source of Truth (DECIDED)

On-chain `PeerStatus` is the single source of truth for peer identity, stake, and primary hostname. The P2P node registry (`[:p2p :nodes]`) provides supplementary off-chain metadata (transport URIs, supported regions, protocol version) validated against on-chain state. Nodes reject P2P entries from unregistered or zero-stake peers.

### 13.5 BeliefLattice Merge Context (OPEN)

**Question**: `BeliefMerge` currently requires consensus state (peer stakes, timestamp ordering) as context for the merge function. How does this integrate with `LatticeContext`?

`LatticeContext` currently carries a signing key pair and an optional verifier. BeliefMerge needs access to the current consensus `State` to check peer stakes and compute voting weights. Options:
- Extend `LatticeContext` with an optional `State` reference
- Have `BeliefLattice` capture the state at construction time (stale-safe since beliefs are merged frequently)
- Pass state through a separate mechanism alongside the lattice path

This needs to be resolved before Phase 2 of the consensus migration.

## 14. Implementation Phases

### Phase 1 — P2P Node Registry
- Implement `NodeInfoLattice` (LWW per owner, validated against on-chain PeerStatus)
- Register `[:p2p :nodes]` in `Lattice.ROOT` with `OwnerLattice<NodeInfo>`
- `NodeServer` propagates P2P metadata via existing LATTICE_VALUE gossip
- Peers advertise transport URIs; `ConnectionManager` uses them for connection selection
- Existing BELIEF messages and `BeliefPropagator` unchanged

### Phase 2 — Consensus as Lattice Region
- Implement `BeliefLattice` wrapping `BeliefMerge` with CPoS semantics
- Register `[:convex <genesis-hash> :peers]` in `Lattice.ROOT`
- Resolve `LatticeContext` integration for consensus state (§13.5)
- `BeliefPropagator` accepts beliefs via both BELIEF and LATTICE_VALUE paths

### Phase 3 — Multi-Transport
- Add WSS transport to Netty (binary protocol over WebSocket)
- Reconnection with exponential backoff and transport failover
- NodeInfo transport URIs drive automatic transport selection

### Phase 4 — Deprecate BELIEF Messages
- LATTICE_VALUE becomes the sole consensus propagation path
- `BeliefPropagator` retains timing loop but speaks standard lattice protocol
- BELIEF message type deprecated

### Future — Kademlia
- Implement `KadLattice` for O(log N) routing when network scale demands it
- Node-local only (not propagated)

## 15. Summary

The Convex peer network is built on lattice technology — the same algebraic foundations, data structures, security model, and propagation mechanisms used by every other part of the ecosystem. On-chain `PeerStatus` is the single source of truth for peer identity and stake.

The design extends this with:

1. **P2P peer discovery** at `[:p2p :nodes]` — `OwnerLattice`-based signed peer metadata, validated against on-chain PeerStatus, propagated via `NodeServer` gossip
2. **Consensus beliefs as a lattice region** at `[:convex <genesis-hash> :peers]` — gradual migration from dedicated BELIEF messages to standard LATTICE_VALUE protocol
3. **NodeServer as the unified P2P core** — all lattice propagation (including consensus) through one mechanism, with `BeliefPropagator` as a timing-specific wrapper
4. **Multi-transport support** — Netty for binary (TCP + WSS), Javalin for HTTP (REST + MCP), with transport advertisement and failover

This means:
- One propagation mechanism (`NodeServer` + `LatticePropagator`) serves all regions
- One security model (SignedData + AccountKey) authenticates everything
- One encoding format (CAD3) on the wire and in storage
- On-chain PeerStatus as the trust anchor; P2P lattice as the operational layer
- Transport is a thin adapter layer beneath the unified lattice protocol
