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

## Node Filtering and Selective Replication

Nodes only replicate feeds they care about:

1. **Compute follow set** — union of active followed keys across all local users
2. **Selective pull** — path-based `LATTICE_QUERY` for `[:social <followed-key>]`
3. **Store as boundary** — unfollowed feeds are not stored and cannot leak to peers

This uses the existing `LatticePropagator` infrastructure with path-based queries. No new
sync protocol is needed.

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
