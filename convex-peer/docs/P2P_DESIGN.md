# Convex Peer Network Design — PROPOSAL

> **Status**: Design proposal with a partial implementation. Sections marked
> **[EXISTS]** describe current implementation; sections marked **[PARTIAL]**
> have a working vertical slice with remaining policy work; sections marked
> **[PROPOSED]** describe new work. Items marked **[DECISION]** require further
> discussion.

## 1. Overview

This document proposes the design for the Convex peer-to-peer network layer. The network enables peers to discover each other, establish authenticated connections over multiple transports, synchronise consensus state, and serve client requests.

The design builds on the existing `convex.node` infrastructure: `NodeServer`
owns authoritative path merges and persistence, while application-configured
`LatticePropagator` groups own protocol processing, filtered views and delta
gossip. The proposal extends this with:

- **Consensus beliefs as a lattice region** — Belief merge (CPoS) becomes part of the unified lattice, not a separate subsystem
- **P2P peer discovery lattice** — signed peer metadata with multiple routing strategies
- **Multi-transport connections** — TCP, WebSocket, HTTPS, SSE with transport advertisement
- **Reconnection and failover** — automatic reconnect with exponential backoff

The core design principle is **separation of the message protocol from the transport layer**. All transports carry the same CAD3-encoded binary protocol messages — the transport is simply the delivery mechanism.

## 2. Current Architecture [EXISTS]

The implementation-level ownership, lifecycle and connection-state map for
`NodeServer`, `LatticePropagator` and `LatticeConnectionManager` is maintained in
[LATTICE_NETWORKING.md](LATTICE_NETWORKING.md). This proposal uses its terms
`desired`, `pending`, `active`, `assigned`, `trusted` and `upgraded` precisely.

### 2.1 Lattice ROOT

The global base lattice is defined by `Lattice.ROOT` (`KeyedLattice`). See [LATTICE_REGIONS.md](../../convex-core/docs/LATTICE_REGIONS.md) for the canonical region listing, types, and paths.

Each region uses the same algebraic foundations: join-semilattices, SignedData verification, delta transmission, and structural sharing via immutable persistent data structures with CAD3 encoding.

### 2.2 NodeServer

`NodeServer<V>` replicates an assembled `LatticeHost`. It owns the durable node
root and isolated notifications to attached policy groups; the host's
applications own path merges. Each group's `LatticeProtocolEndpoint` handles:

- **LATTICE_VALUE** — `[:LV [*path*] value]` is an optimistic push; `[:LV id [*path*] value]` is a confirmed push returning a post-merge Result
- **LATTICE_QUERY** (`[:LQ id [*path*]]`) — Respond with current value at a lattice path
- **DATA_REQUEST** (`[:DR id hash1 hash2 ...]`) — Serve content-addressable data from the store
- **PING** (`[:PING id]`) — Liveness check returning `Result(id, timestamp)`

Key features across the host and its groups:
- **Authenticated missing data recovery** — verified source connections may resolve
  missing cells through correlated requests; unverified sources must send complete values
- **Copy-on-write cursor** — atomic updates via `cursor.updateAndGet()`
- **LatticeContext** — carries signing keys through the lattice hierarchy for `OwnerLattice`/`SignedLattice` verification
- **LatticePropagator** — an application-configured propagation policy group
  owning its routes, serving store, filters and protocol endpoint

`NodeServer` is schema-independent. It does not recognise `:p2p`, publish NodeInfo,
refresh discovery state or apply social/PoP policy. `P2PNode` composes those concerns;
its `NodeDirectory` translates validated `[:p2p :nodes]` records into the generic
connection candidates accepted by `LatticeConnectionManager`.

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
| 1 | CHALLENGE | Signed Ed25519 auth challenge `[nonce, responderKey, context]` |
| 2 | RESPONSE | Legacy response tag; lattice challenges return a correlated RESULT |
| 3 | DATA | Content-addressable delta data; unverified network DATA is rejected |
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
| 14 | LATTICE_VALUE | Optimistic push `[:LV [*path*] value]` or confirmed push `[:LV id [*path*] value]` |
| 15 | LATTICE_QUERY | Query lattice value `[:LQ id [*path*]]`; the path is always a vector |
| 16 | PING | Connectivity check `[:PING id]`, returning `Result(id, timestamp)` |

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

## 4. P2P Discovery Lattice [PARTIAL]

### 4.1 Lattice Paths

P2P discovery and routing use three paths, each keyed by node key:

```
[:p2p :nodes <node key>]  → SignedData<NodeInfo>        Node records this node keeps
[:kad <node key>]         → SignedData<routing view>    Each node's own Kademlia view   [FUTURE]
[:local <node key> :p2p]  → local evidence              What this node has seen of others  [PARTIAL]
```

Node records and routing views are shared. Each entry is signed by its node key and
merges by the standard owner rule, and each node holds only the entries it wants
(§4.2). `:local` holds application state that is not shared (§4.6).

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
{:timestamp   1708000000000            ;; owner's clock, milliseconds
 :transports  ["tcp://peer.convex.world:18888"
               "wss://peer.convex.world/ws"]
 :pops        [0xdef456...]             ;; node keys providing a return path
 :relay       true                     ;; willing to relay bounded signed messages
 :regions     #{:p2p :id :kad :social}  ;; top-level lattice regions this node serves
 :type        "Convex Lattice Node"
 :version     "0.8.18"}                ;; software version
```

`:transports` may be an empty vector. That is an explicit, signed statement that
the node is identifiable but not directly dialable, as for an outbound-only node
behind NAT. Private or guessed addresses must not be advertised as public transports.
`:pops` contains at most 16 unique remote node keys and `:relay` defaults to false
when absent. These fields advertise routing intent; they do not authenticate or open
a connection. `:regions` names the top-level regions the node serves, so other nodes
can choose peers that serve what they need (§4.5). It never includes `:local`.

The record format and its validation belong to the P2P application in
`convex-p2p`, which merges the registry through its own component.

#### Merge Function

Standard `OwnerLattice` merge with additional NodeInfo-specific rules:

- **Signature verification** via `OwnerLattice` — signer must match owner key
- **Timestamp monotonicity** — newer NodeInfo replaces older (LWW per owner)
- **Admission** — any correctly self-signed record is accepted. Optional stronger
  proofs, and local policies that use them, are proposed in
  [#713](https://github.com/Convex-Dev/convex/issues/713).
- **Retention** — each node keeps only the records it wants (below).

The owner's `:timestamp` orders only that owner's own records. A node whose clock
falls behind its last published record cannot replace that record until its clock
passes it. The timestamp is never evidence that the node is alive (§4.6).

#### Retention

Each node holds only the node records it wants, as it does for social owners. The
P2P application's component for the region, `NodeRegistry`, defines the registry's
merge: the owner rule, applied only to the records the node wants. It holds no local
state: the application supplies the blocklist check and the contact standings from
`:local` (§4.6), and the node supplies the nodes to keep. Nodes
that accept every record converge under the owner rule; a node that turns records
away holds a subset of theirs. An incoming record that differs from the one held is
checked before it is merged, and the node publishes only what it holds. There is no
global directory: each node passes on the records it keeps, so discovery spreads as
gossip of samples, and the registry's cost to a node stays bounded however many keys
exist.

A node always keeps, without counting them:

- its own record;
- records of nodes the operator named with `connect`;
- records of nodes it has an authenticated route to, including NAT leaves that
  connected in, and of a node whose inbound route it is authenticating;
- records of its own Points of Presence.

These are bounded by configuration and by the node's connection limit. Beyond them
it keeps up to `maxNodeRecords` records (1024 by default, in the node configuration),
in order: nodes that answered and have not failed since, then nodes never reached,
then nodes that failed. The registry is a directory, not a connection list, so this
bound is independent of how many peers the node connects to. Nothing an incoming
value or connection can create escapes a bound. Within each group the order is a
hash of the node key salted with a secret the node draws when it starts. Other nodes
cannot predict the order, so none can choose which records a node keeps. It is
stable while the node runs, so a record turned away is turned away again when it
next arrives, and exchanges between nodes settle. The evidence comes from `:local`
(§4.6), never from the record itself.

The checks on an incoming record run cheapest first:

1. a record of a node this node has blocked (§4.6) is refused;
2. a record over 4 KB (`P2PLattice.MAX_NODE_RECORD_SIZE`) is refused;
3. a new node competing for a place in a full registry is refused if it ranks below
   the lowest record held;
4. a record that is not newer under the owner rule is ignored;
5. a record that is not a valid NodeInfo is refused, leaving any held one.

The node's own record skips the size and validity checks. Held records are not
checked again; they leave only when outranked or blocked. Each node keeps a different
subset, so every exchange offers records turned away before, and each costs a
comparison. A dropped record leaves the node directory but stays a connection
candidate.

A NAT leaf pushes its record before the receiving node authenticates its route, so a
full registry may turn the record away. The receiving node keeps the leaf's record
while it authenticates the route, and merges the pushed record again. If the
authentication fails, the record competes like any other at the next merge.

A node can route a point message only through records it keeps. Finding an
arbitrary node by key is Kademlia's job (§4.3).

#### Node identity and Convex peers

A P2P node is identified by its node key alone. Its signed NodeInfo needs no
on-chain registration or stake, and no node rejects a record for lacking either.

A node key may also belong to a Convex peer registered in on-chain `PeerStatus`.
That is orthogonal to discovery, connection and routing. Local policy may treat it
as extra trust, as one of the optional proofs proposed in #713, and on-chain peer
entries are one optional bootstrap source (§6.3).

**Implemented bootstrap path**: An operator gives `P2PNode.connect` one node by a peer
name, which the node resolves with its records and blocklist and keeps
([LATTICE_NETWORKING.md](LATTICE_NETWORKING.md#connection-capabilities)), or by node
AccountKey and TCP address. After challenge/response verifies that endpoint, the
connecting node pushes only its own signed `[:p2p :nodes]` entry and waits for a
post-merge acknowledgement. It then pulls and merges the bootstrap node's `:p2p` and
`:id` regions plus each currently desired complete social-owner slot. A late joiner
therefore receives its follow-filtered view without an unrestricted full-root pull or
waiting for another publication. The receiving node independently challenges the connecting
node over the same full-duplex socket. Only after the live connection proves the key
of the valid signed NodeInfo it delivered is that physically inbound connection upgraded
into an outbound propagation route. A node with empty `:transports` therefore supports
bidirectional gossip without a reverse TCP connection.

**Seed nodes**: A node joins a network through its seed list, kept in its local
record (§4.6), with `P2PNode.seed`. The list holds at most 16 peer names. The seeds
its configuration lists under `seeds`, each a `url` with an optional `key`, are added
to it at launch, and `addSeed` and `removeSeed` change it while the node runs.
Whenever the node has no route, as when it starts, it reads the list, and each seed's
name is resolved and the seed connected and initialised as with `connect`; a seed
that cannot be reached, or loses its route, is resolved again and retried after a
backoff while it stays listed. A seed added while the node is seeding is connected to
at once. A `did:key` seed is found where the node's records or contact addresses
place its key.
Once the node's own ambient slots hold routes to other peers, at least one, which can
replace the seeds, it releases every seed: the route it opened closes, and a seed whose
record or contact address it holds stays an ordinary candidate. Seeds so
carry a node only while it joins or rejoins, not for good; in a network too small to
fill the slots, they stay connected. A restarted node needs no seed (§4.6).

**Proposed on-chain bootstrap path** (optional): A node may take seeds from the
on-chain peer list, the keys and `:url` hosts in `PeerStatus`, alongside configured
seeds and retained records. Each connection is authenticated by the same challenge/response
for the listed key, and the node then pulls the P2P registry as from any bootstrap
peer.

### 4.3 Kademlia Routing [FUTURE]

Kademlia provides O(log N) lookup of any node by key, using XOR distance. The `Kademlia` utility class already provides `proximity()` and `distance()` functions. `KadLattice` exists as a stub.

Routing state is owner-keyed. `[:kad <node key>]` holds one node's signed routing
view, its k-buckets. Each node maintains only its own view, because the view depends
on its own key position, but views are shareable: another node can read a view to
find nodes closer to the key it is looking for. A node keeps the views it wants, as
it does node records. The region stays reserved (`ReservedLattice`) until the view's
format and merge are designed.

This is planned for when the network outgrows what retained node records can serve.
For current network sizes (up to hundreds of nodes), strategies 1-3 in §4.4 are
sufficient.

### 4.4 Peer Discovery Strategies

A node uses multiple strategies to discover and maintain connections, in order:

1. **Configured peers** — Seed nodes from the node's seed list, which its
   configuration adds to, held only until other peers can replace them (§4.2), and
   nodes the operator names with
   `P2PNode.connect`, which are kept. Every connection is authenticated by
   challenge/response.

2. **On-chain peer registry** [PROPOSED, optional] — `PeerStatus` entries in
   consensus state name registered Convex peers' keys and `:url` hosts. A node may
   use them as bootstrap seeds; they confer no P2P identity.

3. **P2P node registry** (`[:p2p :nodes]`) — Each node's own signed transport
   metadata, passed on by every node that keeps it (§4.2). This is where discovery
   happens once a node has bootstrapped.

4. **Kademlia lookup** [FUTURE] — For discovering specific peers by AccountKey when direct routing is needed. O(log N) hops. Not needed at current network scale; planned for future growth.

### 4.5 Selective Attention

Nodes only propagate lattice regions they participate in. A lightweight data node need not propagate consensus beliefs. A consensus validator need not propagate DLFS file systems. Each node record's `:regions` names the top-level regions that node serves, so a node can prefer peers that serve what it needs. Region-aware peer selection is [PROPOSED].

This is natural in the `NodeServer` model: the application attaches propagators
with the appropriate filtered views, while a node only merges values governed by
its configured lattice.

### 4.6 Local Evidence [PARTIAL]

What a node has seen of other nodes is its own business. It lives at
`[:local <own node key> :p2p]`: `:local` holds application state that is not meant
to be shared, written and managed by one application instance. The node may
replicate it for backup, but never publishes it to other nodes or merges theirs: the
P2P application's projection leaves `:local` out in both directions. Leaving it out is
not secrecy: its cells sit in the node's store like any others, and `DATA_REQUEST`
serves any stored cell to a node that names its hash.

`ContactRecords` holds a contact record per node key: the last authenticated contact,
the address this node last dialled and found the node's key at, the failures since
that contact and the last failure.

```clojure
;; Value at [:local <own node key> :p2p]
{:timestamp 1708000000000
 :contacts  {0xabc123... {:last-contact 1708000000000, :failures 0,
                          :address "tcp://198.51.100.20:18888"}
             0xdef456... {:last-contact 1707990000000, :failures 2,
                          :last-failure 1707999000000}}
 :blocked   {0x789abc...                {}
             "tcp://203.0.113.5:18888"  {}
             "198.51.100.7"             {}}
 :seeds     {"did:web:seed.example.com"       {}
             "tcp://seed2.example.com:18888"  {:key 0xabc123...}}}
```

Only an authenticated connection counts as contact: a verified outbound admission or
an inbound route upgrade, and then each `keepAlive` interval (60 seconds by default)
in which the route is heard from or answers a probe. A failure is a dial or identity
challenge that failed for a desired peer, or an authenticated route that stopped
answering. Nothing the owner writes in its node record, such as `:timestamp`, ever
counts. Only a verified route this node dialled records an address: a route the other
node opened says nothing about where it can be reached.

Only live connections, which hold a socket and a slot, are probed. A node that
answered and has not failed since keeps that standing whether or not this node is
connected to it: time alone is no evidence against it, and nothing dials it just to
check.

Nodes connecting in can create records, so at most 1024 nodes have one
(`ContactRecords.MAX_NODES`). A new node beyond that replaces one with failures if
there is one, otherwise the one this node has been out of touch with longest. A live
route keeps reporting contact, so it is never the oldest.

`LatticeConnectionManager` sees every dial, challenge and silence and reports contact
and failures through `setContactHandlers`. It knows no lattice paths. The P2P node
routes the reports to the application's `ContactRecords`, an `ALocalComponent`: each
report edits its private copy and signs nothing. The application syncs the copy into
its `:local` slot, signing the slot once, whenever it syncs the host to publish its
own record, and when the service shuts down as [SHUTDOWN.md](SHUTDOWN.md) prescribes;
a service that skips that sync loses what changed since. The host persists the slot
with the rest of its root, so after a restart the records start from what the host
retained. A node without a key keeps none. At launch each node with a recorded address
becomes a connection candidate there, so a restarted node rejoins any node it dialled
without a seed, using only its own records. The registry is retained the same way, and
its records become candidates too; a node record names newer transports than a contact
address.

The same record holds, at `:blocked`, the node keys, transport addresses and hosts
the operator blocked with `P2PNode.block`, as an `AccessList`
([LATTICE_APPLICATIONS.md](../../convex-core/docs/LATTICE_APPLICATIONS.md#data-and-merge-boundaries)).
The record is replaced whole, so an unblock survives a restart and an older copy of
the record cannot bring back a removed block. `block` and `unblock` sync the host at
once; durability is the service's, as for contacts. A node without a key keeps its
list in memory. What an entry refuses:

- a node key: its record (§4.2), its connections, including one it opened, being a
  connection candidate, and point messages from it, to it or through it;
- a transport address, such as `tcp://host:port`: dialling it;
- a host: dialling any address at it and, for an IP address, inbound connections
  from it, which `serveAllInbound` turns away. A host name is matched as written,
  without DNS, so it refuses only dialling.

An address is stored in one form whatever its spelling: scheme and host in lower
case, the port explicit, an IP address in standard text. The P2P node checks the
list wherever it hands the connection manager a peer, which holds no list itself
(§8.2). Blocking a node closes its connections at once, and a blocked node that
connects in is closed when it claims its key, or dropped if it authenticates.
Blocking an address drops the nodes with a route at it or advertising it, and
offers them again at their other addresses. An unauthenticated inbound connection
already open when its address is blocked stays open until it closes, with no route.

The same record holds, at `:seeds`, the node's seed list (§4.2): an `AccessList` from
each peer URI to an entry that may name the key the peer must prove. Each distinct URI
is one seed, though several may reach the same node. It is the live list, read each
time the node seeds; configuration only adds to it, and `addSeed` and `removeSeed`
change it and sync the host at once. A node without a key keeps its list in memory.

Proposed for the same record:

- failures per address, since many keys can name one address;
- blocks the node adds itself, with an expiry, for misbehaviour such as repeated
  failed challenges.

Retention (§4.2) ranks node records by this evidence. Candidate selection
deliberately does not (§8.2).

## 5. Transport Layer [PROPOSED]

### 5.1 Architecture

```
┌─────────────────────────────────────────┐
│            Lattice Regions              │
│  (Consensus, P2P, Data, FS, KV, ...)   │
├─────────────────────────────────────────┤
│       NodeServer (merge + persist)      │
├─────────────────────────────────────────┤
│ LatticePropagator (policy + protocol)  │
├─────────────────────────────────────────┤
│   Binary Protocol Messages (CAD3)       │
├─────────────────────────────────────────┤
│          Transport Adapter              │
├──────┬──────────┬──────────┬────────────┤
│ TCP  │ WebSocket│  HTTPS   │ Streamable │
│:18888│  (WSS)   │ req/resp │ HTTP (SSE) │
└──────┴──────────┴──────────┴────────────┘
```

`NodeServer` owns node-root publication and persistence for an assembled host,
while each `LatticePropagator` owns one filtered replication policy and its
protocol state.
The application composes those groups with shared or independent transport
adapters; the server neither opens sockets nor dispatches transport messages.
This is the key difference from the consensus `Server`, which manages its own
networking resources.

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

A transport URI names where to reach a node, never its key. A node record pairs its
transports with the key that signs it; where an operator expects a key, it is given
beside the URI ([LATTICE_NETWORKING.md](LATTICE_NETWORKING.md#connection-capabilities)).

### 6.2 Transport Selection

Clients select the best available transport from the peer's advertised set:

- **Validators** prefer TCP for consensus (lowest overhead)
- **Browsers** use WSS (native WebSocket support)
- **Simple API callers** use HTTPS
- **AI agents / MCP clients** use WSS or Streamable HTTP

### 6.3 On-Chain Bootstrap [PROPOSED, optional]

On-chain `PeerStatus` lists registered Convex peers with their keys and a `:url`
host. A node may use these as bootstrap seeds alongside configured seeds and retained
records. They are not a source of P2P identity or transports: the node authenticates
each one by challenge/response for the listed key, and learns transports from the P2P
registry once connected.

**Bootstrap flow**:

1. Read the on-chain peer list: keys and `:url` hosts
2. Connect to listed hosts, expecting the listed key
3. Pull the P2P node registry (`[:p2p :nodes]`) as from any bootstrap peer
4. Continue with candidates discovered from the registry

## 7. Authentication [EXISTS + PROPOSED]

### 7.1 Raw TCP (Peer-to-Peer) [EXISTS]

Every owner-authenticated lattice value is wrapped in **SignedData** and validated
against its owner before merge. This end-to-end integrity does not, however, make a
new TCP connection trusted or grant it routing and store capabilities.

An `AConnection` starts untrusted. Operator assignment may permit its inbound messages
to one propagator view, but this is access policy rather than peer authentication. A
connection receives trusted-peer limits or becomes an outbound propagation route only
after challenge/response proves the live remote node key.

### 7.2 Ed25519 Challenge/Response [EXISTS]

The lattice challenge protocol verifies that the connecting party possesses the private
key for a claimed AccountKey:

```
Challenger                           Responder
  │                                     │
  ├── CHALLENGE Signed([nonce,           │
  │       responderKey, context]) ──────→│
  │                                     │
  │←── RESULT Signed([nonce,             │
  │       challengerKey, context]) ──────┤
  │                                     │
  └── AUTHENTICATED ROUTE                │
```

The nonce is generated with `SecureRandom`. The responder key is the challenge audience,
the challenger key is the response audience, and `convex-lattice-peer-v1` is the fixed
domain context. Both signatures, the exact vector shape, nonce, audience and context are
verified. A fresh challenge is required on every connection.

### 7.3 Inbound-to-outbound route upgrade [EXISTS]

TCP is full-duplex regardless of which node opened the socket, but physical direction
and logical capability are kept separate:

```
INBOUND / UNTRUSTED
    │ operator assigns one inbound propagator view
    │ signed NodeInfo is accepted for key K
    │ live challenge/response proves K on this connection
    ▼
INBOUND / AUTHENTICATED / OUTBOUND-PROPAGATION ROUTE
```

`NodeServer` owns the physical inbound connection throughout. The explicit upgrade
adds it to `LatticeConnectionManager`'s outbound broadcast routes; it does not turn it
into a `Convex` client or transfer connection ownership. The application vouches for
the key it expects on that connection (P2P: the connection delivered that key's own
valid signed NodeInfo), and the upgrade happens only after the connection proves that
key. Desired-peer membership plays no part, so discovered candidates cannot block an
upgrade. The upgrade method rejects an untrusted connection. Disconnect
revokes both the trust-bound route and all ordinary inbound per-connection state.

Manager-owned outbound clients have the mirror behaviour. After authenticating their
remote endpoint, they may deliver unsolicited reverse lattice messages into their
owning propagator. This path does not consult the arbitrary-inbound selector because
the manager already selected and authenticated that peer; it is still bounded by the
normal NodeServer ingress queue and merge policy.

### 7.4 Re-authentication on Reconnect

On reconnect, challenge/response is re-run (cheap: one Ed25519 sign + verify). The peer can restore session state (subscriptions, pending responses) keyed by AccountKey.

## 8. Connection Management [EXISTS + PROPOSED]

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

For lattice nodes, `LatticeConnectionManager` separately maintains identity-keyed
desired, pending and admitted connections. A configured node key keeps a socket in
capability-free limbo until challenge/response succeeds. Desired peers are of two
kinds. Explicit peers come from the operator through `connectPeer` and `addPeer` and
are kept until removed or, as for seeds, released. Discovered peers are only
candidates for ambient slots: in the P2P application, `NodeDirectory` validates correctly owner-signed `[:p2p :nodes]` records
and translates their transport fields. Discovery never makes a node a wanted peer,
and a node with no supported transport to dial, such as a NAT leaf, is not a
candidate. The connection manager itself never reads that lattice path or parses NodeInfo,
and holds no blocklist: the P2P node offers a candidate only at the addresses it does
not refuse, never offers or connects to a blocked node, and drops one that
authenticates (§4.6). Callers
can await admission without polling. Discovered candidates are bounded by the
configurable `maxDesiredPeers` cap (256 by default); explicit peers are not counted,
and a full candidate pool never refuses explicit intent or an inbound route upgrade.
Vacant ambient slots are filled from the candidates at random, so connections stay
spread across the network rather than settling into fixed clusters.
That cap bounds candidates, not live connections. The soft live targets are 16
ambient peers plus up to 16 recently active communicators (`ambientPeers` and
`activePeers` in `LatticePropagatorConfig`). Explicit connections may exceed them.
Maintenance retains healthy incumbents, prioritises directed application traffic,
and fills failed slots from other candidates while the failed peer backs off.
See [live peer selection](LATTICE_NETWORKING.md#live-peer-selection) for activity,
liveness and pending-attempt rules.
It also keeps authenticated inbound connections in a separate upgraded-route map.
Ordinary inbound sockets never appear in broadcasts; an upgraded route is selected
only when no live manager-owned outbound client already serves the same peer key.
A peer reached only through an upgraded route takes no ambient or active slot, so
nodes that connect in cannot displace the peers this node chose.

### 8.3 Reconnection [EXISTS — lattice nodes]

Automatic reconnection with exponential backoff:

- **Initial delay**: 1 second
- **Max delay**: 30 seconds (exponential growth with jitter)
- **Max retries**: Currently unlimited for desired lattice peers. Separate configured
  and discovered retry policies remain proposed.

Per message type:
- **Transactions**: Fail-fast on disconnect. Caller must handle sequence number management.
- **Queries**: Fail-fast. Idempotent — caller can re-issue.
- **Lattice subscriptions**: Auto-resubscribe on reconnect.

### 8.4 NAT traversal and Points of Presence [PARTIAL]

The implemented first step supports an outbound-only leaf behind NAT:

1. The leaf publishes signed NodeInfo with empty `:transports`.
2. It opens and maintains an outbound TCP connection to a dialable node.
3. The dialable node authenticates the leaf and upgrades that same inbound socket to
   an outbound propagation route as described in §7.3.
4. Both nodes send lattice deltas and missing-data requests over the one full-duplex
   connection. No reverse dial, STUN or hole punching is required.

This is sufficient for lattice propagation when the leaf can keep at least one
connection to a public node. The first Point of Presence layer is also implemented:
a leaf advertises its PoP node keys in `:pops`, a willing node advertises `:relay true`,
and the PoP forwards bounded point messages without replacing their end-to-end source
signature. Private bodies use ECIES and outgoing relay hops use only authenticated
node routes. See
[Points of Presence](../../convex-p2p/docs/POINTS_OF_PRESENCE.md) for the current wire
format, API and limits.

Public relay deployment, discovery and operational policy remain tracked in
[issue #710](https://github.com/Convex-Dev/convex/issues/710). A production relay
service still needs redundant provider selection, abuse policy and monitoring.
Direct communication between two NATed nodes may later add ICE/STUN candidate exchange
and hole punching, with relay fallback still required for CGNAT and symmetric NAT.

### 8.4 Transport Failover [PROPOSED]

If a peer advertises multiple transports in `[:p2p :nodes]`, `Convex.connect()` can attempt them in preference order on failure:

1. WSS (full-duplex, TLS)
2. HTTPS (stateless, TLS)
3. TCP (raw binary, no TLS)

On reconnect, if the current transport repeatedly fails, the next is tried.

## 9. Generic NodeServer beneath P2P [EXISTS]

### 9.1 Architecture

`NodeServer` remains schema-independent: it replicates an assembled host and
merges nothing itself. The calling application constructs its propagation groups
and transport hosts, while P2P policy stays above all three layers:

```
P2PNode / NodeDirectory (P2P schema, discovery and policy)
├── NodeServer (host-root publication, persistence and notifications)
├── LatticeListener (application-owned inbound TCP and group routing)
└── LatticePropagator (application-configured replication policy group)
    ├── LatticeProtocolEndpoint (CAD036 queues, acquisition and trust)
    └── LatticeConnectionManager (external routes, explicit peers and bounded candidates)
```

### 9.2 Message Routing

Messages are currently split between propagation-group endpoints (generic lattice
protocol), P2P application handlers and `Server` (consensus and client-facing).
As more message types migrate to the lattice protocol, propagators may transport
an increasing share without `NodeServer` acquiring knowledge of their records or
connection sets.

**LatticeProtocolEndpoint** handles lattice messages for its selected group:

| Message | Handler | Notes |
|---------|---------|-------|
| LATTICE_VALUE | `processLatticeValue()` → `cursor.path(path).merge(value)` | Navigate, merge, then respond when ID is non-null |
| LATTICE_QUERY | `processLatticeQuery()` | Respond with value at path |
| DATA_REQUEST | `processDataRequest()` | Serve content-addressable data |
| PING | `processPing()` | Respond with `Result(id, timestamp)` |

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
| TCP :18888 | None | SignedData per value | Challenge/response for connection capabilities |
| WSS :443 | TLS | TLS + SignedData | Challenge/response |
| HTTPS :443 | TLS | TLS + SignedData | Challenge/response or bearer token |
| SSE :443 | TLS | TLS + SignedData | Session ID + challenge/response |

Raw TCP carries only peer-to-peer lattice traffic (beliefs, peer metadata, data sync) which is individually signed and public. TLS-protected transports are used for client-facing traffic where query results or transaction details may be sensitive.

### 10.3 Conditional Acceptance via LatticeContext [EXISTS]

Nodes apply acceptance rules during lattice merges via `LatticeContext`:

- **Always**: reject invalid signatures (enforced by `OwnerLattice`/`SignedLattice`)
- **Always**: reject non-monotonic timestamps (enforced by LWW merge)
- **Optional**: local admission policies using stronger proofs, such as Convex peer
  status ([#713](https://github.com/Convex-Dev/convex/issues/713))
- **Optional**: rate limiting to prevent metadata spam

These rules are enforced locally. Invalid entries are silently discarded and cannot propagate through the network because every honest node independently validates.

## 11. Node Lifecycle

1. **Identity**: The node uses its own node key. A node that is also a Convex peer may use its registered peer key; registration is optional
2. **Bootstrap**: Connects to configured seeds or named peers, to the records it retained before a restart or, optionally, to on-chain peer hosts
3. **Advertisement**: Publishes `SignedData<NodeInfo>` to `[:p2p :nodes]`
4. **Discovery**: Discovered by other nodes via P2P gossip (and optionally Kademlia); each keeps the record if it wants it
5. **Connection**: Nodes establish connections using advertised transport URIs
6. **Participation**: The node joins lattice regions (consensus, data, etc.) via selective attention
7. **Departure**: A newer record with no transports takes it out of other nodes' candidates; an unreachable node keeps its record while attempts on it back off ([#814](https://github.com/Convex-Dev/convex/issues/814)); a Convex peer withdraws its stake on-chain separately

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

Kademlia routing is deferred until network scale demands it. Discovery through configured peers and the P2P node registry is sufficient for hundreds of nodes. When implemented, Kademlia state will be owner-keyed at `[:kad <node key>]`: each node maintains its own view, since it depends on its key position, and views are shareable (§4.3).

### 13.3 Server Architecture — Netty + Javalin (DECIDED)

Netty handles binary protocol (TCP + WSS): high-performance peer-to-peer lattice traffic. Javalin handles HTTP-based APIs (REST + MCP + SSE): web-friendly with JSON, middleware, CORS. Both frameworks connect to the same `NodeServer` and `Server` instances.

### 13.4 Node Identity Is the Node Key (DECIDED)

A P2P node is identified by its self-signed node key; no on-chain registration or
stake is required, and the node registry (`[:p2p :nodes]`) is not validated against
on-chain state. Being a Convex peer registered in `PeerStatus` is orthogonal: local
policy may treat it as extra trust (#713), and on-chain peer entries are one optional
bootstrap source (§6.3).

### 13.5 BeliefLattice Merge Context (OPEN)

**Question**: `BeliefMerge` currently requires consensus state (peer stakes, timestamp ordering) as context for the merge function. How does this integrate with `LatticeContext`?

`LatticeContext` currently carries a signing key pair and an optional verifier. BeliefMerge needs access to the current consensus `State` to check peer stakes and compute voting weights. Options:
- Extend `LatticeContext` with an optional `State` reference
- Have `BeliefLattice` capture the state at construction time (stale-safe since beliefs are merged frequently)
- Pass state through a separate mechanism alongside the lattice path

This needs to be resolved before Phase 2 of the consensus migration.

## 14. Implementation Phases

### Phase 1 — P2P Node Registry
- Implement the node registry (LWW per owner, signed by each node key)
- Register `[:p2p :nodes]` in `Lattice.ROOT` with `OwnerLattice<NodeInfo>`
- `NodeServer` propagates P2P metadata via existing LATTICE_VALUE gossip
- Peers advertise transport URIs; `ConnectionManager` uses them for connection selection
- Existing BELIEF messages and `BeliefPropagator` unchanged

### Phase 1b — Retention and Local Evidence
- Node records advertise served regions (`:regions`, §4.2)
- Contact records reported by the connection manager and kept in `:local` (§4.6)
- Each node keeps only the records it wants, ranked by that evidence (§4.2)
- Seed nodes released once other peers replace them, and rejoining from retained records (§4.2)
- Operator blocklists of node keys and addresses in the same record (§4.6)

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
- Owner-keyed: each node publishes its own routing view at `[:kad <node key>]`

### Future — Shared Blocklists
- Publish blocklists for other nodes to subscribe to, as an owner-signed `AccessList`
  in its own region, unlike a node's private list in `:local` (§4.6)
- A node chooses whose lists it follows, and reads them together with its own

## 15. Summary

The Convex peer network is built on lattice technology — the same algebraic foundations, data structures, security model, and propagation mechanisms used by every other part of the ecosystem. A P2P node's identity is its own signed node key; being a Convex peer as well is optional and orthogonal.

The design extends this with:

1. **P2P node discovery** at `[:p2p :nodes]` — `OwnerLattice`-based metadata signed by each node key, propagated via `NodeServer` gossip, with each node keeping the records it wants
2. **Consensus beliefs as a lattice region** at `[:convex <genesis-hash> :peers]` — gradual migration from dedicated BELIEF messages to standard LATTICE_VALUE protocol
3. **NodeServer as the unified P2P core** — all lattice propagation (including consensus) through one mechanism, with `BeliefPropagator` as a timing-specific wrapper
4. **Multi-transport support** — Netty for binary (TCP + WSS), Javalin for HTTP (REST + MCP), with transport advertisement and failover

This means:
- One propagation mechanism (`NodeServer` + `LatticePropagator`) serves all regions
- One security model (SignedData + AccountKey) authenticates everything
- One encoding format (CAD3) on the wire and in storage
- Each node's own key as its identity, with Convex peer status as optional extra trust
- Transport is a thin adapter layer beneath the unified lattice protocol
