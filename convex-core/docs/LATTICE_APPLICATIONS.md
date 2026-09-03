# Lattice Application Patterns

How to build an application on the Convex Data Lattice: design a lattice for the data,
wrap it in typed components over cursors, and connect it to a local store or a hosting
node without the application knowing which. The patterns are specified in
[CAD045](https://docs.convex.world/docs/cad/lattice_apps); this document is the
practical companion for `convex-core`, with `convex-social`, `convex-dlfs` and
`convex-p2p` as worked examples. Cursor internals are in
[LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md).

## Key points

- Design the state tree first, then choose a merge strategy for each leaf and compose
  lattices bottom-up. `path()` and `zero()` on your lattice are what make cursors
  navigate and auto-initialise correctly.
- Wrap per-user data in `OwnerLattice` so every owner's slot is signed by that owner
  and forgeries are rejected on merge.
- One component, one path. An `ALatticeComponent` holds a cursor at exactly one lattice
  path and exposes domain operations; an `ALatticeApplication` composes regions at the
  root; a facade spanning paths contains components rather than being one.
- Applications never call merge directly. They write through cursors and let the
  cursor chain handle signing, typing and propagation.
- Accept a `RootComponent`, not a `NodeServer` or `AStore`. The same application code
  runs over a local store or a networked node.
- Persist, sync and flush are three different boundaries: persistence writes cells,
  sync merges and publishes, flush is physical durability.
- Under union merges a removed key resurrects, so tombstone; for durable deletes use a
  whole-value LWW leaf.
- The `LatticeContext` is the write policy: who signs, what time it is, which signers
  may act for which owners. One logical write resolves the clock once.

## Architecture

```
Domain components        DLFSDrive, Social        one component per path
Application component    ALatticeApplication      region composition
Root component           RootComponent            persistence and publication
Cursor and lattice       path(), fork(), merge    state and merge semantics
Optional host runtime    NodeServer               replication and lifecycle
```

`RootComponent` is the developer-facing host. It always has an `AStore` and owns the
root publication policy but not the store lifecycle. `NodeServer` can host the same
root and installs its replication pipeline during launch; application branches never
know a node exists.

## Designing the lattice

**Start from the data model.** Sketch the tree, for example a social network:

```
:social (OwnerLattice)
  └── <ownerKey> (SignedData)
        └── Index<Keyword, ACell>            per-user record
              ├── :feed    → Index<Blob, ACell>        posts by timestamp
              ├── :profile → AHashMap<Keyword, ACell>  profile fields
              └── :follows → AHashMap<ACell, ACell>    follow records
```

**Choose merge strategies bottom-up.**

| Primitive | Use when | `zero()` | `merge()` |
|---|---|---|---|
| `LWWLattice` | Single overwritten values (profiles, posts) | `null` | Higher timestamp wins |
| `IndexLattice` | Ordered collections keyed by blob (feeds, logs) | `Index.EMPTY` | Key union, child merge per entry |
| `MapLattice` | Unordered collections (follows, metadata) | `Maps.empty()` | Key union, child merge per entry |
| `SetLattice` | Grow-only sets (tags, memberships) | `Sets.empty()` | Set union |
| `OwnerLattice` | Per-owner signed namespaces | `Maps.empty()` | Per-key merge with signature verification |

```java
IndexLattice<Blob, ACell> FEED_LATTICE = IndexLattice.create(LWWLattice.INSTANCE);
MapLattice<ACell, ACell> FOLLOWS_LATTICE = MapLattice.create(LWWLattice.INSTANCE);
```

**Write a custom lattice for structured records.** When a node has named children with
different strategies, extend `ALattice` and implement `merge` (child by child), `path`
(the sub-lattice at each key) and `zero` (the correctly typed empty container).
`path()` is what lets cursors navigate and auto-initialise; without it a child has no
lattice semantics.

**Wrap with `OwnerLattice`** for self-sovereign data:

```java
OwnerLattice<Index<Keyword, ACell>> SOCIAL_LATTICE = OwnerLattice.create(SocialLattice.INSTANCE);
```

**Register with the root.** A node opts in by adding the lattice under a keyword; the
keyword becomes the first path element. Existing regions are catalogued in
[LATTICE_REGIONS.md](LATTICE_REGIONS.md).

```java
KeyedLattice root = Lattice.ROOT.addLattice(Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);
```

## Building the application layer

**Compose from a generic host.** Application code depends on `RootComponent` only:

```java
public final class MyApplication extends ALatticeApplication<Index<Keyword, ACell>> {
    private final Social social;
    private final DLFSRegion files;

    private MyApplication(RootComponent<Index<Keyword, ACell>> host) {
        super(host);
        social = Social.connect(this);
        files = DLFSRegion.connect(this, Keyword.intern("documents"));
    }
    public static MyApplication connect(RootComponent<Index<Keyword, ACell>> host) {
        return new MyApplication(host);
    }
}

// Local
RootComponent<Index<Keyword, ACell>> local = RootComponent.open(applicationLattice, store);
MyApplication app = MyApplication.connect(local);

// Hosted by a node: identical application code
NodeServer<Index<Keyword, ACell>> node = new NodeServer<>(applicationLattice, store, config);
MyApplication networked = MyApplication.connect(node.getRootComponent());
node.launch();
```

The caller owns and closes `store` and `node`; components are views and own neither.
`NodeServer` must serve from the root component's store so published roots never
reference data the primary store lacks. Publication policy is a lifecycle concern: a
root defaults to local store publication, and network infrastructure installs and
freezes its policy during launch.

**One component, one path.** Each level of the data model is an `ALatticeComponent`
subclass holding a cursor at one path and navigating one level deeper with
`cursor.path(key)`:

```
Social        cursor at OwnerLattice level
  SocialUser  cursor at SocialLattice level (through the signing boundary)
    Feed      cursor at IndexLattice<Blob, ACell>
    Follows   cursor at MapLattice<ACell, ACell>
```

The component parent supplies application policy and host store access; the cursor
parent supplies navigation and synchronisation. They are deliberately separate: a fork
keeps its component parent while its cursor syncs to the live cursor it was forked
from. Signing is transparent: `Feed` never sees `SignedData`.

Constructors must copy caller-supplied path arrays. Long-lived application and region
components are fine; owner and session views should be held by the service that routes
them or recreated on demand, never accumulated in an unbounded cache.

**Write through cursors.** Use `updateAndGet` for read-modify-write; the lambda
receives `lattice.zero()` for an uninitialised path, so no null guard is needed:

```java
public Blob post(String text) {
    long ts = System.currentTimeMillis();
    Blob key = SocialPost.createKey(ts);
    cursor.updateAndGet(feed -> feed.assoc(key, SocialPost.createPost(text, ts)));
    return key;
}
```

`cursor.assoc(key, value)` and `cursor.assocIn(value, keys...)` cover simple writes and
auto-initialise intermediates from `zero()`. Reads use `cursor.get()` and
`cursor.get(keys...)`, which return null for absent paths.

**Keep record construction in static helpers** (`SocialPost.createPost`), separating
data format from cursor mechanics. Rules of thumb:

- Always include `:timestamp` in LWW-merged records; it is the tiebreaker.
- Use `Keyword` keys for record fields and `Blob` keys (big-endian timestamps) for
  ordered collections in an `Index`.
- Use `Index` for lattice-level containers where `path()` and `zero()` matter, and
  `AHashMap` for leaf records and dynamic key sets. `Index` resolves `Keyword` and
  `AString` keys identically, which matters for JSON interoperability.

## Fork and sync

A fork is an independent working copy; `sync()` merges it back through its cursor
parent with lattice semantics and always succeeds. This is separate from publication
and durability:

```java
Social forked = social.fork();
forked.user(myKey).feed().post("Post 1");
forked.user(myKey).feed().post("Post 2");
forked.sync();   // merge into the live component
app.sync();      // publish the hosted root
app.flush();     // optional physical durability barrier
```

Domain components expose `fork()` with their exact type when batching is a use case;
`sync()` is inherited from `ALatticeComponent`.

| Operation | Meaning | Moves a cursor? |
|---|---|---|
| `component.persist()` | Write reachable cells through the host store; return the store-backed value | No |
| `component.sync()` | Merge through the cursor parent; at the root, run publication policy | Yes, where merge selects a value |
| `application.flush()` | The store's physical durability barrier | No |

Incremental writers may persist at intervals to swap direct references for
store-backed soft references and relieve memory pressure; they must install the
returned value themselves. Persistence neither selects a retained root nor licenses
garbage collection.

## Write policy: `LatticeContext`

The context answers who signs, what time it is and which signers act for which
owners. Install it once on the application or root cursor and every descendant
inherits it live. A fixed context suits deterministic tests; an application policy
resolves dynamically from a clock, wallet or key store and must be thread-safe if
shared. The `with...` methods (`withTimestamp`, `withSigningKey`, `withOwnerVerifier`,
`withMaxFutureTimestampSkew`) override one capability and delegate the rest.

```java
cursor.setContext(LatticeContext.create(null, myKeyPair));                  // runtime clock
cursor.setContext(LatticeContext.create(CVMLong.create(1000), myKeyPair));  // fixed clock
```

**Time.** `currentTimestamp()` is resolved once per logical write, even under retry,
and components consume it exactly: no incrementing, no comparing with stored values to
invent a later one, no hidden logical clock. Reusing a timestamp deliberately creates a
tie, which the local (`own`) operand wins. Where ordering across replicas matters,
especially delete followed by recreate, the application supplies timestamps that
express it.

**Signing.** `signAs(owner, value)` is the single authorship rule and is exactly what
`verifyOwner` checks on merge. An `AccountKey` owner requires that key; an indirect
owner (address, DID) is resolved by the owner verifier, lenient when none is installed.
A write the policy cannot author throws `IllegalStateException`, so a slot no peer would
accept never reaches local state.

## Security model

`OwnerLattice` maps owner keys to `SignedData<V>` and, on merge, rejects any value
whose signer does not match the owner. The same rule runs when a value is authored: a
`SignedCursor` write for an owner the context cannot sign for throws and stores
nothing. A merge that would have to synthesise a value for an owner this node cannot
sign for keeps its own value instead; merging validly signed data never fails and never
attaches a non-owner signature.

Local state is trusted in the sense that matters: nothing polices what an owner writes
into their own slot, only that slots are written in a form peers accept. Write
adversarial tests that construct forged state at the raw data level and assert that
`OwnerLattice.merge` drops it.

## Deletes

Additive merges union keys, so a removed key returns when an older replica merges
back. Under `IndexLattice`, `MapLattice` or `SetLattice`, delete by tombstone: set a
`:deleted` field and bump `:timestamp` so the deletion wins.

For durable deletes, model the region as a single whole-value LWW leaf built from
three single-concern layers:

```java
ALattice<ACell> state = StampingLattice.create(
    LWWLattice.create(JSONLattice.INSTANCE, tsFn),   // whole-value merge over JSON navigation
    (v, ts) -> v.assoc(KEY_TIMESTAMP, ts));          // stamp the context timestamp on every write
```

`JSONLattice` provides structural navigation below the leaf, `LWWLattice` merges the
whole value by `:timestamp` so a smaller map with a newer timestamp replaces the old
one, and `StampingLattice` inserts a `StampedCursor` so every deep write re-stamps the
leaf. A delete is then a read-modify-write that dissociates the key.

## Testing

- **Standalone**: exercise components over an in-memory root with a fixed context.
- **Connected**: assert that writes propagate to the root cursor.
- **Fork and sync**: changes in a fork are invisible until `sync()`, then merged.
- **Multi-user**: different owners' data is independent.
- **Adversarial**: forgeries are rejected at `OwnerLattice.merge`.

```java
Social forked = social.fork();
forked.user(key).feed().post("In fork");
assertEquals(0, social.user(key).feed().count());
forked.sync();
assertEquals(1, social.user(key).feed().count());
```

Follow the repository conventions: no sleeps, wait on real signals; no fixed ports.

## Checklist

- [ ] Design the state tree, then choose a merge strategy per leaf.
- [ ] Compose lattice primitives bottom-up; write a custom `ALattice` with `merge`,
      `path` and `zero` for structured records.
- [ ] Wrap with `OwnerLattice` for per-user ownership; register under a root keyword.
- [ ] Derive an `ALatticeApplication`; one `ALatticeComponent` per lattice path;
      facades contain components.
- [ ] Accept `RootComponent` in factories; provide local `open()` and host-neutral
      `connect()`.
- [ ] Write with `updateAndGet`, navigate with `cursor.path(key)`, return exact types
      from `fork()`.
- [ ] Treat persist, sync and flush as distinct boundaries.
- [ ] Include `:timestamp` in LWW records; tombstone under union merges.
- [ ] Write standalone, connected, fork/sync, multi-user and adversarial tests.

## Where the code lives

- `convex.lattice.ALatticeApplication`, `ALatticeComponent`, `RootComponent` — the
  component layer.
- `convex.lattice.LatticeContext`, `OwnerLattice`, `SignedLattice`, `StampingLattice`,
  `JSONLattice`, `LWWLattice`, `IndexLattice`, `MapLattice`, `SetLattice` — lattices
  and write policy.
- `convex.node.NodeServer` (`convex-peer`) — the host runtime.
- `convex-social` (`Social`, `SocialUser`, `Feed`, `SocialPost`) and `convex-dlfs` —
  worked examples.

## Related

- [CAD045 Lattice Applications](https://docs.convex.world/docs/cad/lattice_apps) — the normative patterns.
- [CAD024 Data Lattice](https://docs.convex.world/docs/cad/data_lattice) and [CAD036 Lattice Node](https://docs.convex.world/docs/cad/lattice_node) — lattice model and hosting.
- [CAD038 Lattice Authorisation](https://docs.convex.world/docs/cad/lattice_auth) — signer authorisation.
- [LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md) and [LATTICE_REGIONS.md](LATTICE_REGIONS.md).
