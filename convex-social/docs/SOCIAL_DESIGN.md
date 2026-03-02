# Social Lattice Design

A peer-to-peer social network built on Convex lattice technology. Each user owns a
cryptographically signed feed that only they can write to. Nodes selectively replicate
feeds based on follow relationships, and timelines are constructed by merging followed
feeds by timestamp.

The base layer is minimal and extensible — easy to layer applications and UI on top.

## Lattice Architecture

The social lattice composes standard Convex lattice primitives into a two-level structure:

```
Root KeyedLattice (per-node)
  :social → OwnerLattice<SocialLattice>
                <owner-key-A> → SignedData<SocialLattice value>
                <owner-key-B> → SignedData<SocialLattice value>
                ...

SocialLattice (per-user, signed by owner):
  :feed    → IndexLattice<Blob, ACell>      8-byte timestamp keys, LWW per entry
  :profile → LWWLattice                     display name, bio, avatar, etc.
  :follows → MapLattice<ACell, ACell>       followed key → {active, timestamp}
```

### OwnerLattice

The `OwnerLattice` wraps each user's data in `SignedData` — only the owner's Ed25519 key
can sign updates. This is the same pattern used by `:fs`, `:kv`, and `:queue` in
convex-core. Foreign data (signed by a different key) is rejected during merge.

### Node Opt-In

The social lattice is **not** part of convex-core's `Lattice.ROOT`. Nodes opt in by
composing their root lattice to include `:social`:

```java
KeyedLattice root = Lattice.ROOT.addLattice(Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);
```

## Feed

### Key Format

Feed entries are keyed by **8-byte big-endian timestamp Blobs**:

```
[8 bytes: milliseconds since epoch, big-endian]
```

Big-endian encoding gives lexicographic = chronological ordering in `Index`, so feeds
are naturally sorted by time. The single-writer guarantee (enforced by `OwnerLattice`)
means timestamps are monotonic with no collision risk.

`Index.slice()` enables efficient time-range queries for pagination without scanning the
entire feed.

### Post Format

Each post is an `AHashMap<Keyword, ACell>`:

| Key          | Type              | Required | Description                              |
|--------------|-------------------|----------|------------------------------------------|
| `:text`      | AString           | Yes      | Post text content                        |
| `:timestamp` | CVMLong           | Yes      | Milliseconds since epoch                 |
| `:reply-to`  | Blob (8 bytes)    | No       | Timestamp key of parent post             |
| `:reply-did` | ABlob             | No       | Account key of parent post's author      |
| `:media`     | AVector\<Hash\>   | No       | References to blobs in `:data` lattice   |
| `:tags`      | ASet\<AString\>   | No       | Hashtags                                 |
| `:deleted`   | CVMLong           | No       | Deletion timestamp (tombstone marker)    |

### Merge Semantics

The feed uses `IndexLattice<Blob, ACell>` with `LWWLattice` per entry:

- **New posts** (different keys) — union; both kept
- **Edited posts** (same key, different `:timestamp`) — LWW; latest version wins
- **Deleted posts** — owner updates entry adding `:deleted` field; LWW resolves naturally
- **Undelete** — owner updates again without `:deleted`; latest `:timestamp` wins

Timeline construction filters out entries where `:deleted` is present.

## Profile

A single LWW register (`LWWLattice.INSTANCE`):

| Key          | Type    | Description                          |
|--------------|---------|--------------------------------------|
| `:timestamp` | CVMLong | Last update time                     |
| `:name`      | AString | Display name                         |
| `:bio`       | AString | User bio                             |
| `:avatar`    | Hash    | Reference to image in `:data` lattice|
| `:url`       | AString | Website URL                          |

The entire profile is replaced atomically — the version with the latest `:timestamp` wins.

## Follows

`MapLattice<ACell, ACell>` with `LWWLattice` per value:

```
{<followed-account-key> {:timestamp 1708444800000 :active true}
 <another-account-key>  {:timestamp 1708445000000 :active false}}
```

The `:active` flag enables follow/unfollow toggling. `MapLattice` + LWW means the latest
timestamped record for each followed key wins. This was chosen over `SetLattice` because
sets are grow-only — they cannot represent unfollowing.

## Lattice Propagation and Filtering

### How Lattice Sync Works (Background)

`LatticePropagator` handles the output pipeline for a lattice node. It owns a store, a
connection manager, and a background thread. The pipeline is:

1. **Announce** — write cells to store, collect novelty (cells the store hasn't seen)
2. **Persist** — `setRootData()` for crash recovery
3. **Merge callback** — feed store-backed refs back into the cursor
4. **Broadcast** — delta-encode novelty and send `LATTICE_VALUE` to peers

The message protocol has two relevant types:

- **`LATTICE_VALUE`** `[:LV [path...] value]` — push a value (optionally at a path)
- **`LATTICE_QUERY`** `[:LQ id [path...]]` — request a value at a path

Both carry a **path vector**. `NodeServer` already handles path-based queries: it calls
`cursor.get(path)` for queries and `lattice.path(path)` + `PathCursor` for merges.

### Path Resolution Through the Social Hierarchy

Path-based access navigates through the lattice type hierarchy **and** the data structure:

```
Path: [:social <owner-key>]

Lattice resolution (for merge semantics):
  KeyedLattice.path(:social)     → OwnerLattice
  OwnerLattice.path(<owner-key>) → SignedLattice
  SignedLattice.path(:value)     → SocialLattice
  SocialLattice.path(:feed)      → IndexLattice<Blob, ACell>

Data resolution (for get/set):
  cursor.get(:social, <owner-key>)
    → RT.getIn(rootValue, [:social, <owner-key>])
    → navigates Index → AHashMap → returns SignedData<SocialLattice value>
```

`OwnerLattice.path()` returns the same `SignedLattice` for all owner keys — it resolves
the lattice *type*, not a per-owner view. The actual per-owner data selection happens
via `RT.getIn()` navigating the map. This means path-based queries and merges at
`[:social <owner-key>]` work correctly: the data is specific to that owner, and the merge
semantics are the same `SignedLattice` for everyone.

### Pulling Feeds from Followed Accounts

A node that wants to replicate a specific user's feed sends a path-based `LATTICE_QUERY`:

```
LATTICE_QUERY: [:LQ <id> [:social <followed-key>]]
```

The responding node's `NodeServer.processLatticeQuery()` calls `cursor.get(:social,
<followed-key>)`, which returns just that owner's `SignedData<SocialLattice>`. The
requester then announces it to their store and merges at the same path via `PathCursor`.

The pull flow for a social node:

1. **Compute follow set** — `SocialHelpers.computeFollowSet()` unions active followed keys
   across all local users
2. **For each followed key**, send a path-based `LATTICE_QUERY` to connected peers
3. **Merge response** at path `[:social <followed-key>]` — `NodeServer.mergePathWithAcquire()`
   handles missing data recovery automatically
4. **Trigger broadcast** — the merged value propagates to other connected peers

This requires extending `LatticePropagator.pull()` to accept a path parameter. Currently
it always sends an empty path (full root query). The extension is minimal:

```java
// New method on LatticePropagator
public CompletableFuture<ACell> pull(Convex peer, ACell... path) {
    // Same as pull(peer) but with path in the LATTICE_QUERY payload
    AVector<?> pathVector = Vectors.create(path);
    AVector<?> queryPayload = Vectors.create(
        MessageTag.LATTICE_QUERY, queryId, pathVector);
    // ... rest is identical
}
```

### Supporting Nodes That Pull Our Feeds

When a peer sends a `LATTICE_QUERY` with path `[:social <owner-key>]`, `NodeServer`
already handles it — no changes needed. `processLatticeQuery()`:

1. Extracts the path vector from the message
2. Calls `cursor.get(path)` to navigate into the local data
3. Announces the result to the store (so `DATA_REQUEST` can resolve children)
4. Returns the value via `RESULT`

Any node with social data can serve path-based queries out of the box.

### Push Filtering (Outbound)

For push (broadcast), the current `LatticePropagator` always broadcasts the full root
value. For social, this means every connected peer receives every user's feed — wasteful.

`LatticeFilter` is defined for this purpose but not yet wired in:

```java
@FunctionalInterface
public interface LatticeFilter<V extends ACell> {
    V filter(V value);
}
```

Integration into `LatticePropagator.processValue()` would apply the filter before
broadcast, so only relevant data leaves the node. The unfiltered value is still persisted
locally and fed to the merge callback.

However, push filtering alone isn't sufficient for social — a node can't know which feeds
each peer wants. The better model is **pull-dominant** with periodic push of metadata:

1. **Push**: broadcast root hash or lightweight summary (periodic root sync already does
   this every 30 seconds)
2. **Pull**: peers detect divergence from root sync and pull specific paths they care about

### Store as Security Boundary

The store is the security boundary for outbound data. `DATA_REQUEST` messages from peers
can only resolve cells that exist in the propagator's store. A node can run two
propagators with different stores:

```java
// Primary propagator: full data, local persistence
LatticePropagator primary = new LatticePropagator(persistentStore);

// Public propagator: filtered data only
LatticePropagator publicProp = new LatticePropagator(publicStore);
```

Cells not announced to the public store are invisible to peers connecting through it —
even if they guess the hash. This is how nodes prevent leaking private/local-only data.

### Summary: What Works Today vs. What Needs Extension

| Capability | Status | Detail |
|-----------|--------|--------|
| Path-based `LATTICE_QUERY` processing | **Works** | `NodeServer.processLatticeQuery()` handles arbitrary paths |
| Path-based `LATTICE_VALUE` merge | **Works** | `NodeServer.mergePathWithAcquire()` with sub-lattice resolution |
| Path resolution through `KeyedLattice → OwnerLattice → SignedLattice` | **Works** | `lattice.path(path)` navigates the hierarchy correctly |
| Missing data recovery | **Works** | Automatic retry with `acquireFromPeers()` |
| Store-based security boundary | **Works** | `DATA_REQUEST` only resolves store contents |
| Periodic root sync (divergence detection) | **Works** | Every 30 seconds via `maybePerformRootSync()` |
| `LatticePropagator.pull()` with path | **Needs extension** | Currently always sends empty path (full root query) |
| `LatticeFilter` integration | **Needs wiring** | Interface defined, not connected to `processValue()` |
| Subscription model (peer interest) | **Future** | Peers declare which owner keys they want |

The pull-based model is the natural fit for social: nodes pull feeds they follow, serve
feeds they have when asked, and use periodic root sync for divergence detection. The
protocol and merge infrastructure already support path-based operations — the main
extension needed is adding a `path` parameter to `LatticePropagator.pull()`.

## Timeline Construction

Timelines are built by K-way merging sorted `Index` iterators from followed feeds:

1. For each followed user's feed, reverse-iterate from a pagination cursor
2. Maintain a max-heap of `(timestamp, author, entry)` cursors
3. Pull `limit` entries from the heap, filtering out tombstoned posts
4. **Complexity:** O(N log K) where N = page size, K = number of followed feeds

Pagination is cursor-based: pass the timestamp of the last entry on the current page as
`beforeTimestamp` to fetch the next page.

## Module Structure

```
convex-social/
  pom.xml
  docs/
    SOCIAL_DESIGN.md                   This document
  src/main/java/convex/social/
    Social.java                        Lattice definition + KEY_SOCIAL constant
    SocialLattice.java                 Per-user keyed lattice (:feed, :profile, :follows)
    SocialPost.java                    Post and follow record construction helpers
    SocialHelpers.java                 Timeline building, follow-set computation
    TimelineEntry.java                 Record for merged timeline entries
  src/test/java/convex/social/
    SocialLatticeTest.java             Comprehensive tests (18 tests)
```

### Dependencies

- `convex-core` — lattice primitives, data structures, crypto
- `convex-peer` — peer infrastructure (for future integration)

### Key Classes

| Class | Role |
|-------|------|
| `Social` | Static entry point. Defines `KEY_SOCIAL` and `SOCIAL_LATTICE` for node composition |
| `SocialLattice` | `ALattice<Index<Keyword, ACell>>` — merges `:feed`, `:profile`, `:follows` by delegating to child lattices |
| `SocialPost` | Helpers for creating posts, replies, follow records; extracting fields; checking deletion |
| `SocialHelpers` | `buildTimeline()` for K-way merge; `getActiveFollows()` and `computeFollowSet()` for selective replication |
| `TimelineEntry` | `record(AccountKey author, Blob postKey, long timestamp, AHashMap post)` |

## Design Decisions

### Why 8-byte timestamp keys (not composite)?

Each feed has a single writer (the owner), so timestamps are monotonically increasing with
no collision risk. An 8-byte key is simpler and gives the same chronological ordering as a
composite key would. `Index` lexicographic ordering on big-endian longs = time ordering.

### Why tombstone deletion (not removal)?

Lattice merge is monotonic — you cannot remove data, only add. A tombstone (`:deleted`
field with timestamp) fits naturally into LWW merge. The owner can also undelete by
publishing a newer version without the field. Timeline construction filters tombstones at
read time.

### Why MapLattice for follows (not SetLattice)?

`SetLattice` is grow-only — once a key is added, it can never be removed. Follow/unfollow
requires a toggle, which `MapLattice<ACell, ACell>` with LWW per value provides via the
`:active` boolean flag. The latest timestamped record wins.

### Why not in Lattice.ROOT?

Social is an application concern, not a core platform feature. Nodes that do not need
social functionality should not carry the overhead. The `KeyedLattice.addLattice()` helper
makes opt-in composition straightforward.

## Extensibility

The base layer is deliberately minimal. Future extensions can be added as new keys in
`SocialLattice` or as separate lattice sections:

| Feature | Approach |
|---------|----------|
| **Reactions** | New `:reactions` key — `MapLattice<Blob, SetLattice>` (post-key to reaction set) |
| **Media** | Posts reference hashes in the existing `:data` lattice via `:media` field |
| **Threads** | Already supported — `:reply-to` + `:reply-did` fields enable thread traversal |
| **Direct messages** | New `:messages` key with encrypted content |
| **REST API** | Feed/profile/follows/timeline endpoints (convex-restapi extension) |
| **Real-time updates** | Watch paths via existing `StateWatcher` for SSE push |
| **Lists / circles** | Named follow groups for filtered timelines |
