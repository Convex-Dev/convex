# Lattice Application Patterns

Best practices for building applications on the Convex Data Lattice using cursor-based state management. Uses `convex-social` as the reference implementation throughout.

For cursor internals and the cursor class hierarchy, see [LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md).

## Architecture Overview

A lattice application has four layers:

```
┌─────────────────────────────────────────────────┐
│  Application API          Social, Feed, Follows │  Domain-specific wrappers
├─────────────────────────────────────────────────┤
│  Cursor Chain             path(), fork(), sync()│  Navigation + atomic writes
├─────────────────────────────────────────────────┤
│  Lattice Hierarchy        KeyedLattice, Owner…  │  Merge semantics + type info
├─────────────────────────────────────────────────┤
│  Node Infrastructure      NodeServer, pull()    │  Networking + persistence
└─────────────────────────────────────────────────┘
```

Applications never call lattice merge directly. They navigate cursors, read and write values, and let the cursor chain handle signing, type safety, and merge propagation.

## Designing the Lattice

### 1. Start from the data model

Sketch the state tree your application needs. For a social network:

```
:social (OwnerLattice)
  └── <ownerKey> (SignedData)
        └── (Index<Keyword, ACell>)        ← per-user record
              ├── :feed    → Index<Blob, ACell>       posts by timestamp
              ├── :profile → AHashMap<Keyword, ACell>  profile fields
              └── :follows → AHashMap<ACell, ACell>    follow records
```

### 2. Choose merge strategies bottom-up

Pick a lattice primitive for each leaf, then compose upward:

| Primitive | Use when | `zero()` | `merge()` |
|-----------|----------|----------|-----------|
| `LWWLattice` | Single values that get overwritten (profiles, individual posts) | `null` | Higher timestamp wins |
| `IndexLattice` | Ordered collections keyed by blob (feeds, logs) | `Index.EMPTY` | Union of keys, child merge per entry |
| `MapLattice` | Unordered collections (follow lists, metadata) | `Maps.empty()` | Union of keys, child merge per entry |
| `SetLattice` | Grow-only sets (tags, memberships) | `Sets.empty()` | Set union |
| `OwnerLattice` | Per-owner signed namespaces | `Maps.empty()` | Per-key merge with signature verification |

Composition reads naturally from the data model:

```java
// Feed: ordered by timestamp, LWW per post (edits/deletes resolve by timestamp)
IndexLattice<Blob, ACell> FEED_LATTICE = IndexLattice.create(LWWLattice.INSTANCE);

// Follows: unordered map, LWW per entry (follow/unfollow resolves by timestamp)
MapLattice<ACell, ACell> FOLLOWS_LATTICE = MapLattice.create(LWWLattice.INSTANCE);
```

### 3. Write a custom lattice for structured records

When a node has multiple named children with different merge strategies, extend `ALattice` directly:

```java
public class SocialLattice extends ALattice<Index<Keyword, ACell>> {

    @Override
    public Index<Keyword, ACell> merge(Index<Keyword, ACell> own, Index<Keyword, ACell> other) {
        // Merge each child using its specific lattice
        Index<Blob, ACell> mergedFeed = FEED_LATTICE.merge(getFeed(own), getFeed(other));
        ACell mergedProfile = LWWLattice.INSTANCE.merge(own.get(KEY_PROFILE), other.get(KEY_PROFILE));
        AHashMap<ACell, ACell> mergedFollows = FOLLOWS_LATTICE.merge(getFollows(own), getFollows(other));
        // ... reconstruct result
    }

    @Override
    public <T extends ACell> ALattice<T> path(ACell childKey) {
        if (KEY_FEED.equals(childKey))    return (ALattice<T>) FEED_LATTICE;
        if (KEY_PROFILE.equals(childKey)) return (ALattice<T>) LWWLattice.INSTANCE;
        if (KEY_FOLLOWS.equals(childKey)) return (ALattice<T>) FOLLOWS_LATTICE;
        return null;
    }

    @Override
    public Index<Keyword, ACell> zero() {
        return (Index<Keyword, ACell>) Index.EMPTY;
    }
}
```

The `path()` method is critical — it tells cursors what sub-lattice exists at each key, enabling lattice-aware navigation and auto-initialisation (see [LATTICE_CURSOR_DESIGN.md § Auto-initialisation](LATTICE_CURSOR_DESIGN.md#auto-initialisation-via-valuelatticezero)).

### 4. Wrap with OwnerLattice for self-sovereign data

Most applications want per-user ownership with cryptographic signing:

```java
public static final OwnerLattice<Index<Keyword, ACell>> SOCIAL_LATTICE =
    OwnerLattice.create(SocialLattice.INSTANCE);
```

This gives you: owner key → `SignedData<V>` → your application state. The `OwnerLattice` rejects data signed by the wrong key during network merge.

### 5. Register with the root lattice

Applications plug into a node's root `KeyedLattice` under a keyword. See [LATTICE_REGIONS.md](LATTICE_REGIONS.md) for the existing root regions and their lattice types.

```java
KeyedLattice root = Lattice.ROOT.addLattice(Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);
```

This is how a node opts in to hosting your application's data. The keyword (`:social`) becomes the first path element when navigating from the root.

## Building the Application Layer

### The wrapper pattern

Each level of the data model gets a thin wrapper class that holds a cursor and exposes domain operations:

```
Social          → cursor at OwnerLattice level
  SocialUser    → cursor at SocialLattice level (through signing boundary)
    Feed        → cursor at IndexLattice<Blob, ACell> level
    Follows     → cursor at MapLattice<ACell, ACell> level
```

Each wrapper navigates one level deeper via `cursor.path(key)`:

```java
public class Social {
    private final ALatticeCursor<?> cursor;  // at OwnerLattice

    public SocialUser user(AccountKey ownerKey) {
        // path() crosses: OwnerLattice → SignedLattice → SocialLattice
        ALatticeCursor<Index<Keyword, ACell>> userCursor =
            cursor.path(ownerKey, Keywords.VALUE);
        return new SocialUser(userCursor, ownerKey);
    }
}

public class SocialUser {
    private final ALatticeCursor<Index<Keyword, ACell>> cursor;  // at SocialLattice

    public Feed feed() {
        return new Feed(cursor.path(SocialLattice.KEY_FEED), ownerKey);
    }
}
```

The cursor chain handles signing transparently — `Feed` doesn't know about `SignedData` at all.

### Writing through cursors

Use `updateAndGet` for read-modify-write operations. When a lattice is present, the update lambda receives `lattice.zero()` instead of null for uninitialised paths, so you don't need null guards:

```java
// Feed.post() — no null check needed, feed is auto-initialised to Index.EMPTY
public Blob post(String text) {
    long ts = System.currentTimeMillis();
    Blob key = SocialPost.createKey(ts);
    AHashMap<Keyword, ACell> post = SocialPost.createPost(text, ts);
    cursor.updateAndGet(feed -> feed.assoc(key, post));
    return key;
}
```

For simple key-value writes, use `assoc` or `assocIn` on the cursor directly:

```java
cursor.assoc(key, value);              // single key
cursor.assocIn(value, key1, key2);     // nested path
```

These are lattice-aware: with a lattice, null intermediates are auto-initialised from `lattice.zero()`. Without a lattice, null intermediates throw (see [LATTICE_CURSOR_DESIGN.md § assoc/assocIn](LATTICE_CURSOR_DESIGN.md#assoc--associnlattice-aware-writes)).

### Reading from cursors

Use `cursor.get()` for the current value, `cursor.get(keys...)` for nested reads:

```java
public AHashMap<Keyword, ACell> getPost(Blob key) {
    Index<Blob, ACell> feed = cursor.get();
    if (feed == null) return null;        // get() returns null, not zero()
    return (AHashMap<Keyword, ACell>) feed.get(key);
}
```

Note: `get()` returns null for uninitialised paths. The zero-substitution only applies inside update lambdas.

### Static helpers for data construction

Keep post/record construction in static helper classes. This separates data format from cursor mechanics:

```java
public class SocialPost {
    public static final Keyword TEXT = Keyword.intern("text");
    public static final Keyword TIMESTAMP = Keyword.intern("timestamp");

    public static AHashMap<Keyword, ACell> createPost(String text, long timestamp) {
        return Maps.of(TEXT, Strings.create(text), TIMESTAMP, CVMLong.create(timestamp));
    }
}
```

Rules for record design:
- **Always include `:timestamp`** in LWW-merged records — it drives the merge tiebreaker
- **Use `Keyword` keys** for record fields — compact, interned, fast comparison
- **Use `Blob` keys** for collection entries that need ordering (feeds use 8-byte big-endian timestamp blobs for chronological order in `Index`)
- **Tombstone, don't delete** — set a `:deleted` field rather than removing entries, since lattice merge is union-based and can't propagate removals

## Fork/Sync for Batch Operations

Fork creates an independent working copy. Sync merges changes back using lattice semantics (always succeeds). This enables:

- **Batch writes** — multiple updates with a single signing pass
- **Speculative changes** — try operations locally, sync only if successful
- **Concurrent access** — independent forks merge deterministically

```java
// Fork for batch posting
Social forked = social.fork();
forked.user(myKey).feed().post("Post 1");
forked.user(myKey).feed().post("Post 2");
forked.user(myKey).feed().post("Post 3");
forked.sync();  // one merge, one sign
```

Application wrappers should expose fork/sync if batch operations are a use case:

```java
public class Social {
    public Social fork() {
        return new Social(cursor.fork());
    }

    public void sync() {
        cursor.sync();
    }
}
```

See [LATTICE_CURSOR_DESIGN.md § sync() vs CAS-based merge()](LATTICE_CURSOR_DESIGN.md#sync-vs-cas-based-merge) for details on how sync handles concurrent modifications.

## Connecting to Node Infrastructure

Applications can run standalone (own cursor) or connected to a node (shared root cursor):

```java
// Standalone — creates its own root cursor
Social social = Social.create(myKeyPair);

// Connected — navigates from a node's root cursor
Social social = Social.connect(nodeServer.getCursor(), myKeyPair);
```

The connected pattern is how applications participate in the lattice network. Writes propagate up through the cursor chain to the node's root, where `LatticePropagator` broadcasts deltas to peers.

```java
public static Social connect(ALatticeCursor<?> rootCursor, AKeyPair keyPair) {
    LatticeContext ctx = LatticeContext.create(null, keyPair);
    ALatticeCursor<?> socialCursor = rootCursor.path(KEY_SOCIAL);
    socialCursor.withContext(ctx);
    return new Social(socialCursor);
}
```

### LatticeContext

The `LatticeContext` carries the signing key pair and verification policy. Set it on the cursor before any writes that cross a `SignedCursor` boundary:

```java
LatticeContext ctx = LatticeContext.create(null, myKeyPair);
cursor.withContext(ctx);
```

Without a context (or with a context that has no key pair), writes through `SignedCursor` throw `IllegalStateException`.

## Security Model

### What OwnerLattice protects

`OwnerLattice` maps owner keys to `SignedData<V>`. During **network merge** (node-to-node replication), it verifies that the signer key matches the owner key. Forgeries — data signed by key A placed under key B — are silently rejected.

### What cursors don't protect

Cursors trust local writes. If Alice's code writes to Bob's slot locally, the cursor chain signs the data with Alice's key and stores it. The forgery is only detected when this data is merged with another node via `OwnerLattice.merge(context, ...)`.

This is by design: local state is trusted (it's your own node), network state is verified.

### Testing security

Write adversarial tests that construct forged state at the raw data level and verify that `OwnerLattice.merge` rejects it:

```java
// Alice signs data, places it under Bob's key
SignedData<V> forged = alice.signData(fakeState);
AHashMap<ACell, SignedData<V>> attackerNode = Maps.of(bob.getAccountKey(), forged);

// Merge should reject the forgery
AHashMap<ACell, SignedData<V>> merged =
    ownerLattice.merge(context, honestNode, attackerNode);

assertNull(merged.get(bob.getAccountKey()),
    "Forgery should be rejected: signer != owner");
```

## Choosing Data Structures

| Need | Use | Why |
|------|-----|-----|
| Ordered entries (timelines, logs) | `Index<Blob, ACell>` | Sorted radix tree, lexicographic blob ordering |
| Named fields (records) | `Index<Keyword, ACell>` or `AHashMap<Keyword, ACell>` | `Index` when the lattice hierarchy uses it; `AHashMap` for leaf records |
| Dynamic key sets (follows, tags) | `AHashMap<ACell, ACell>` | Hash-based, unordered, efficient merge |
| Append-only sequences | `Index` with monotonic blob keys | Big-endian timestamp keys give chronological ordering |
| Single values (profile, status) | Direct `ACell` with `LWWLattice` | Last-write-wins register |

### Index vs AHashMap

Both are associative, both support `mergeDifferences`. The choice matters for:

- **Lattice hierarchy**: `KeyedLattice` and `SocialLattice` use `Index<Keyword, ACell>` because the lattice root expects `Index`. The cursor's `assocIn` creates containers via `lattice.zero()` — if the lattice returns `Index.EMPTY`, you get an `Index`.
- **Ordering**: `Index` keys are sorted lexicographically (blob order). `AHashMap` keys are unordered.
- **JSON compatibility**: `Index` resolves `Keyword` and `AString` identically (same blob), which matters for JSON interop.

Rule of thumb: use `Index` for lattice-level containers (where `path()` and `zero()` matter), `AHashMap` for leaf records and dynamic collections.

## LWW and Timestamps

`LWWLattice` resolves conflicts by comparing `:timestamp` fields. Best practices:

- **Use `System.currentTimeMillis()`** for timestamps — good enough for distributed LWW
- **Include `:timestamp` in every LWW-merged record** — the default `LWWLattice.INSTANCE` extracts it from `AHashMap` values via the `:timestamp` keyword
- **Tombstone deletions**: add a `:deleted` field and update `:timestamp` so the deletion wins over older versions of the same entry

```java
// Deletion via tombstone
public void delete(Blob postKey) {
    long ts = System.currentTimeMillis();
    cursor.updateAndGet(feed -> {
        AHashMap<Keyword, ACell> post = (AHashMap<Keyword, ACell>) feed.get(postKey);
        if (post == null) return feed;
        post = post.assoc(DELETED, CVMLong.create(ts));
        post = post.assoc(TIMESTAMP, CVMLong.create(ts));  // ensures LWW picks this version
        return feed.assoc(postKey, post);
    });
}
```

## Testing Patterns

### 1. Standalone unit tests

Test application logic without node infrastructure:

```java
AKeyPair kp = AKeyPair.generate();
Social social = Social.create(kp);
Feed feed = social.user(kp.getAccountKey()).feed();
Blob key = feed.post("Hello!");
assertEquals("Hello!", SocialPost.getText(feed.getPost(key)));
```

### 2. Connected integration tests

Test that writes propagate to the root cursor:

```java
KeyedLattice root = Lattice.ROOT.addLattice(Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);
ALatticeCursor<?> rootCursor = Cursors.createLattice(root);
Social social = Social.connect(rootCursor, kp);

social.user(kp.getAccountKey()).feed().post("Propagated");
assertNotNull(rootCursor.get(), "Write should propagate to root");
```

### 3. Fork/sync tests

Test that forked changes merge correctly:

```java
Social forked = social.fork();
forked.user(key).feed().post("In fork");
assertEquals(0, social.user(key).feed().count());  // not visible yet
forked.sync();
assertEquals(1, social.user(key).feed().count());  // merged
```

### 4. Multi-user tests

Test that different users' data is independent:

```java
social.user(alice.getAccountKey()).feed().post("Alice");
social.user(bob.getAccountKey()).feed().post("Bob");
assertEquals(1, social.user(alice.getAccountKey()).feed().count());
assertEquals(1, social.user(bob.getAccountKey()).feed().count());
```

### 5. Adversarial security tests

Test that forgeries are rejected at the network merge boundary (see Security Model above).

## Checklist

When building a new lattice application:

- [ ] Design the state tree (what data, what keys, what nesting)
- [ ] Choose merge strategies for each leaf (LWW, set union, custom)
- [ ] Compose lattice primitives bottom-up (`IndexLattice`, `MapLattice`, etc.)
- [ ] Write a custom `ALattice` for structured records with `merge()` and `path()`
- [ ] Implement `zero()` returning the correct empty container type
- [ ] Wrap with `OwnerLattice` if per-user ownership is needed
- [ ] Register under a keyword in the root `KeyedLattice`
- [ ] Build wrapper classes: one per level, holding a cursor, exposing domain operations
- [ ] Use `updateAndGet` for writes — rely on auto-initialisation, no null guards
- [ ] Use `cursor.path(key)` for navigation — signing is transparent
- [ ] Expose `fork()`/`sync()` on top-level wrapper for batch operations
- [ ] Provide `create()` (standalone) and `connect()` (node-attached) factory methods
- [ ] Include `:timestamp` in all LWW-merged records
- [ ] Tombstone instead of delete
- [ ] Write adversarial tests for `OwnerLattice.merge` forgery rejection
- [ ] Write standalone, connected, fork/sync, and multi-user tests
