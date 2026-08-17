# Lattice Application Patterns

Best practices for building applications on the Convex Data Lattice using typed
components over cursor-based state. `convex-dlfs`, `convex-p2p` and
`convex-social` provide concrete examples.

For cursor internals and the cursor class hierarchy, see [LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md).

## Architecture Overview

A hosted lattice application has five layers:

```
┌─────────────────────────────────────────────────┐
│  Domain components        DLFSDrive, Social     │  One component per path
├─────────────────────────────────────────────────┤
│  Application component    ALatticeApplication   │  Region composition
├─────────────────────────────────────────────────┤
│  Root component           RootComponent         │  Persistence + publication
├─────────────────────────────────────────────────┤
│  Cursor/lattice           path(), fork(), merge │  State + merge semantics
├─────────────────────────────────────────────────┤
│  Optional host runtime    NodeServer             │  Replication + lifecycle
└─────────────────────────────────────────────────┘
```

`RootComponent` is the developer-facing lattice host. It always has an `AStore`
and owns the root publication policy, but does not own the store lifecycle. A
standalone root publishes to that store. `NodeServer` can host the same root and
configures its replication pipeline before launch completes; application branches
do not know that a `NodeServer` exists.

`ALatticeApplication` is the root-level composition point. It shares the hosted
root cursor and attaches independently located regions beneath itself. A domain
`ALatticeComponent` represents one specific lattice path. A convenience facade
that spans unrelated paths should contain multiple components rather than claim
to be a component itself.

Applications never call lattice merge directly. They navigate typed components
and cursors, read and write values, and let the cursor chain handle signing, type
safety and merge propagation.

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

### Compose from a generic host

Keep application code independent of storage and networking implementations:

```java
public final class MyApplication
        extends ALatticeApplication<Index<Keyword, ACell>> {

    private final Social social;
    private final DLFSRegion files;

    private MyApplication(RootComponent<Index<Keyword, ACell>> host) {
        super(host);
        social = Social.connect(this);
        files = DLFSRegion.connect(this, Keyword.intern("documents"));
    }

    public static MyApplication connect(
            RootComponent<Index<Keyword, ACell>> host) {
        return new MyApplication(host);
    }
}
```

The same application can be attached to a local store-backed root or to a root
hosted by network infrastructure:

```java
RootComponent<Index<Keyword, ACell>> local =
    RootComponent.open(applicationLattice, store);
MyApplication app = MyApplication.connect(local);

NodeServer<Index<Keyword, ACell>> node =
    new NodeServer<>(applicationLattice, store, config);
MyApplication networked = MyApplication.connect(node.getRootComponent());
node.launch();
```

The caller owns and closes `store` and `node`. Components are views and own
neither resource.

### One component, one path

Each level of the data model gets an `ALatticeComponent` subclass that holds a
cursor at exactly one path and exposes domain operations:

```
Social          → cursor at OwnerLattice level
  SocialUser    → cursor at SocialLattice level (through signing boundary)
    Feed        → cursor at IndexLattice<Blob, ACell> level
    Follows     → cursor at MapLattice<ACell, ACell> level
```

Each wrapper navigates one level deeper via `cursor.path(key)`:

```java
public class Social extends ALatticeComponent<
        AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> {

    public SocialUser user(AccountKey ownerKey) {
        ALatticeCursor<Index<Keyword, ACell>> userCursor =
            cursor.path(ownerKey, Keywords.VALUE);
        return new SocialUser(this, userCursor, ownerKey);
    }
}

public class SocialUser extends ALatticeComponent<Index<Keyword, ACell>> {

    SocialUser(Social parent, ALatticeCursor<Index<Keyword, ACell>> cursor,
            AccountKey ownerKey) {
        super(parent, cursor);
    }

    public Feed feed() {
        return new Feed(this, cursor.path(SocialLattice.KEY_FEED), ownerKey);
    }
}
```

The component parent supplies containing application policy, including persistence.
The cursor parent supplies logical navigation and synchronisation. These relationships
are deliberately separate: a fork keeps the same component parent while its cursor
synchronises to the live cursor it was forked from. The cursor chain also handles
signing transparently—`Feed` does not know about `SignedData` at all.

Do not retain caller-controlled path arrays; component and cursor constructors must
take an owned copy. Long-lived application and region components are appropriate.
Owner/session views should be retained by the service that routes them, or recreated
cheaply on demand rather than accumulated in an unbounded global cache. Forked
components are normally temporary working views.

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
- **Tombstone under union merges** — with additive merges (`IndexLattice`, `MapLattice`, `SetLattice`) a removed key resurrects when an older replica merges back, so set a `:deleted` field rather than removing entries. Under **whole-value LWW** (see [Durable deletes](#durable-deletes-with-whole-value-lww)) removals *are* durable and you can delete directly.

## Fork/Sync for Batch Operations

Fork creates an independent working copy. Sync merges changes back through the
fork's immediate logical cursor parent using lattice semantics (always succeeds).
That is separate from publishing and durability. This enables:

- **Batch writes** — multiple updates with a single signing pass
- **Speculative changes** — try operations locally, sync only if successful
- **Concurrent access** — independent forks merge deterministically

```java
// Fork for batch posting
Social forked = social.fork();
forked.user(myKey).feed().post("Post 1");
forked.user(myKey).feed().post("Post 2");
forked.user(myKey).feed().post("Post 3");
forked.sync();  // merge into the live Social component
app.sync();     // publish the complete hosted root
app.flush();    // optional physical durability barrier
```

Domain components should expose `fork()` with their exact component type when batch
operations are a use case. `sync()` is inherited from `ALatticeComponent`:

```java
public class Social extends ALatticeComponent<
        AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> {
    public Social fork() {
        return new Social(parent(), cursor.fork());
    }
}
```

See [LATTICE_CURSOR_DESIGN.md § sync() vs CAS-based merge()](LATTICE_CURSOR_DESIGN.md#sync-vs-cas-based-merge) for details on how sync handles concurrent modifications.

### Persistence, publication and durability

The three boundaries have intentionally different meanings:

| Operation | Meaning | Moves a cursor? |
|-----------|---------|-----------------|
| `component.persist()` | Write reachable cells through the host store and return the store-backed value | No |
| `component.sync()` | Merge through its cursor parent; at the root, run host publication policy | Yes, where merge/publication selects a value |
| `application.flush()` | Pass through to the underlying store's physical durability barrier | No |

Incremental blob writers may call the protected persistence mechanism at intervals
to replace eligible direct references with store-backed soft references and relieve
memory pressure. They must explicitly install the returned value in working state.
Persistence neither selects a retained root nor grants permission to garbage collect;
those are application policy.

## Connecting to Host Infrastructure

Application and region components accept components, not `NodeServer`, propagators
or stores. This keeps the ownership direction clear:

```text
runtime/bootstrap owns RootComponent and resource lifecycle
        ↓
ALatticeApplication composes root-level regions
        ↓
ALatticeComponent subclasses provide path-specific domain APIs
```

For a local process, construct or open a `RootComponent` over any `AStore`. For a
networked process, obtain the same abstraction from `NodeServer.getRootComponent()`.
The application code is identical after that point. `NodeServer` must use the root
component's store as its primary serving store, so published roots never contain
references unavailable to that primary.

Host publication configuration is a lifecycle concern. A root defaults to local
store publication, allowing useful pre-launch sync. Network infrastructure installs
and freezes its publication policy during launch; application code cannot silently
replace that policy later.

### LatticeContext

The `LatticeContext` carries the write/merge context: a **signing key pair** (+ verification policy) and a **timestamp** — the single write clock. Set it on the cursor before any writes that cross a `SignedCursor` boundary (which needs the key) or a `StampedCursor` / stamp-on-write region (which needs the timestamp):

```java
// signing only
LatticeContext ctx = LatticeContext.create(null, myKeyPair);

// signing + write clock — needed for stamp-on-write regions; refresh per write
// batch so LWW sees fresh timestamps (the pattern DLFSAdapter uses)
LatticeContext ctx = LatticeContext.create(CVMLong.create(System.currentTimeMillis()), myKeyPair);
cursor.withContext(ctx);
```

A write through `SignedCursor` with no key pair throws `IllegalStateException`; likewise a write through a `StampedCursor` with no timestamp in the context. (The same context timestamp is what `DLFSLocal` reads for node write times.)

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

### Durable deletes with whole-value LWW

Additive merges (`IndexLattice`/`MapLattice`) union keys, so a removed key reappears when an older replica merges back — hence tombstones. When you need **real deletions** to survive merge-back, model the region as a single **whole-value LWW leaf**, composed from three single-concern lattice layers:

```java
// merge = whole-value LWW (deletions durable) · nav = JSON structure · write = stamp
ALattice<ACell> state = StampingLattice.create(
    LWWLattice.create(JSONLattice.INSTANCE, tsFn),   // whole-value merge over JSON navigation
    (v, ts) -> v.assoc(KEY_TIMESTAMP, ts));          // inject the context timestamp on every write
```

- `JSONLattice` — recursive structural navigation; `assocIn` builds containers by key shape, so sub-paths below the leaf stay navigable and writable.
- `LWWLattice(inner)` — merges the *whole* value by `:timestamp` (never per-key), so a smaller map with a newer timestamp replaces the old one and the deleted key does not resurrect.
- `StampingLattice(inner, stampFn)` — inserts a `StampedCursor` so every deep write re-stamps the whole leaf with the timestamp from the `LatticeContext` (the single write clock); the `stampFn` only says *where* to put it. Whole-value LWW then picks it.

Each layer adds exactly one concern (merge / navigation / stamping) and delegates the rest, so they compose freely — `StampingLattice` stamps over anything, `LWWLattice` merges over any navigable inner. To delete, read-modify-write the sub-path out — e.g. `cursor.updateAndGet(m -> m.dissoc(key))` — and whole-value LWW propagates the removal.

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
KeyedLattice lattice = Lattice.ROOT.addLattice(
    Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);
RootComponent<Index<Keyword, ACell>> root = RootComponent.create(lattice, store);
root.cursor().setContext(LatticeContext.create(null, kp));
Social social = Social.connect(root);

social.user(kp.getAccountKey()).feed().post("Propagated");
assertNotNull(root.cursor().get(), "Write should propagate to root");
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
- [ ] Derive an `ALatticeApplication` as the root-level region composition point
- [ ] Build `ALatticeComponent` subclasses: one lattice path per component
- [ ] Keep multi-path conveniences as facades containing path-specific components
- [ ] Accept `RootComponent`, not `NodeServer` or `AStore`, in application factories
- [ ] Use `updateAndGet` for writes — rely on auto-initialisation, no null guards
- [ ] Use `cursor.path(key)` for navigation — signing is transparent
- [ ] Return exact component types from `fork()`; inherit `sync()`
- [ ] Treat component sync, application publication and store flush as distinct boundaries
- [ ] Delegate `persist()` through component parents without moving cursors
- [ ] Let the routing/service layer retain bounded owner or session components
- [ ] Provide local `open()` and host-neutral `connect(RootComponent)` factories
- [ ] Include `:timestamp` in all LWW-merged records
- [ ] Tombstone instead of delete
- [ ] Write adversarial tests for `OwnerLattice.merge` forgery rejection
- [ ] Write standalone, connected, fork/sync, and multi-user tests
