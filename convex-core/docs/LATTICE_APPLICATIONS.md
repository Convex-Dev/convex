# Lattice Application Patterns

How to build an application on the Convex Data Lattice: design a lattice for the data,
wrap it in typed components over cursors, and connect it to a local store or a hosting
node without the application knowing which. The normative reference is
[CAD045](https://docs.convex.world/docs/cad/lattice_apps); this document is the
practical companion for `convex-core`, with `convex-social`, `convex-dlfs` and
`convex-p2p` as worked examples. Cursor internals are in
[LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md).

## The design from first principles

**The problem.** Many nodes hold copies of shared data and update them
independently, with no central authority ordering writes. Any node must be able to
combine any two versions of the data into one, deterministically, so that all copies
converge whatever the order or repetition of exchange. Users own parts of that data,
and nobody else may forge changes to them.

**State is an immutable value.** The whole data tree is one immutable CVM value:
maps, indexes, vectors, sets and signed envelopes. Changing state means producing a
new value and swapping a reference to it. Nothing is ever mutated in place.

**A lattice is the combining rule.** For each position in the tree, a lattice says
how two versions of the value there combine: a merge that is associative, idempotent
and commutative, with one documented exception, that on an exact tie the value
already held locally is kept. Those properties are what make convergence independent
of order. A lattice also knows the empty value at its position and the rule for each
child key, so the lattice for a whole tree is composed bottom-up from a few
primitives: a register where the newer timestamp wins, collections that take the
union of keys and merge each entry by its own rule, records with named children, and
owner namespaces. Design the data tree first, choose a rule for each leaf, and
compose. A custom merge is written only for semantics the primitives cannot express.
Lattices are pure functions over values and know nothing about cursors, components
or storage.

**Ownership is a lattice rule.** Self-sovereign data lives in regions keyed by owner
identity, each slot holding a signed envelope. The merge rule at that level verifies
that the signer is authorised for the owner, by key equality or by a predicate bound
to that lattice instance, and silently drops anything else. The same rule applies
when the local node authors a value: a write into an owner's slot must be signed by
an authorised key or it fails, so local state never contains something peers would
reject. Authorisation belongs to the schema at that path, and a child rule never
waives an enclosing one.

**Policy is separate from mechanism.** A write needs a clock and a signer. The
context supplies both: one timestamp per logical write, used exactly and never
ratcheted against stored values, and the signing capability this process has,
possibly a wallet holding several identities. Reusing a timestamp is a deliberate
tie, which the local value wins. Policy is held by the component doing the work, not
by the data and not by the storage.

**Cursors are the mutable layer, and only that.** A cursor is a reference to one
immutable value with atomic update, compare-and-set, navigation into sub-positions
whose writes rebuild the enclosing value atomically, and private drafts for
multi-step transactions. A cursor knows nothing about lattices, ownership, policy or
persistence. It gives atomicity and navigation; meaning comes from whoever holds it.

**Components give positions their meaning.** A component holds one cursor at one
path together with everything the cursor does not know: the lattice at that level,
the policy, and the domain operations such as post, follow or create drive. One
component per level of the data model. Each creates the components for the level
below, declaring the child's lattice and placing any boundary, signing or
timestamping, exactly where the data model puts it. Boundaries are never discovered
by navigation. There are two kinds of component. A lightweight view shares its
parent's state, so its writes are visible immediately and it has nothing to publish.
A publication boundary owns private working state plus a destination: its sync takes
one snapshot, reconciles it with whatever the destination now holds using the
lattice, with local edits as the preferred side, installs the result by
compare-and-set, and stops. The signed component is the archetype: unsigned working
state, a signed destination, one signature at publication however many edits
preceded it.

**Application policy chooses composition and lifecycle.** A root application can
admit incoming values once, using a composed lattice and its ownership rules.
Domain applications can then observe that accepted root through live path views,
or reconcile staged components with it on their own schedule. They need not each
receive the incoming value. Selective incoming handlers remain useful when an
application needs its own admission or owner-specific reconciliation policy.

**The host is the authoritative local root.** It owns the store, the publication
policy that runs on its sync, and the durability barrier. It knows no lattice or path structure: all incoming values are delegated to
configured applications, and without applications they are ignored. A node adds networking
around the same host: it receives values from peers, hands them to the host, and
sends root snapshots out. Application code is identical against a local host and a
networked one.

### How state moves

- **A local write** goes domain operation, component, cursor atomic update. Any
  stamping or signing happens inside that update at the boundary the component
  wrapped.
- **Publication is explicit and layered.** A component's sync publishes one snapshot
  into its destination and stops there. An application chooses which component
  boundaries to sync and in what order. It need not hold a whole-root snapshot. The host's
  sync persists the root and notifies replication. Flush is the physical durability
  barrier. Persist, sync and flush are three different things, and nothing is
  published, persisted or sent without the owner of that state asking.
- **An incoming value** arrives at the node, reaches the host, and is offered to
  the configured incoming handlers. A single live root application can verify and
  merge it into the host; existing live views then observe the result. Staged
  components remain private until their lifecycle reconciles them. Receipt never
  invokes their sync or automatically publishes their private edits.
- **Batching** is a fork: edit privately, publish once, and let conflicts resolve by
  merge rather than by failure. Transactions are the stricter form for one atomic
  multi-step change, committed by compare-and-set and retried.
- **Deletion** under a union merge needs a tombstone, or a whole-value register
  with a timestamp, because an older replica would otherwise resurrect the key.

**Branch prototype:** component-owned working state and publication are an
intentional revision of CAD035 and CAD045, which still describe lattice-aware
cursors and automatic cursor signing boundaries. Those CADs need corresponding
updates before this design becomes normative. Ownership remains governed by CAD038.

## Key points

- Design the state tree first, then choose a merge strategy for each leaf and compose
  lattices bottom-up. A component declares its lattice when it creates the cursors
  for the level below it; that declaration is what lets a write create a missing
  level with the right container type.
- Wrap per-user data in an owner lattice so every owner's slot is signed by that
  owner and forgeries are rejected on merge.
- One component, one path. A component (`ALatticeComponent`) holds a cursor at
  exactly one lattice path and exposes domain operations; an application component
  (`ALatticeApplication`) composes regions at the root; a facade spanning paths
  contains components rather than being one.
- Applications admit incoming root values by merging them under their lattice.
  Domain writes use cursors for atomicity; publication boundaries use the
  component's lattice to reconcile conflicts.
- Attach to a host (`LatticeHost`), never to a store or a node directly. Explicit
  create and open factories on the host manage retained state. Domain components
  remain independent of the node runtime.
- Persist, sync and flush are three different boundaries: persistence writes cells,
  sync merges and publishes, flush is physical durability.
- Under union merges a removed key resurrects, so tombstone; for durable deletes use
  a whole-value LWW leaf.
- The context (`LatticeContext`) is the write policy: who signs, what time it is,
  which signers may act for which owners. One logical write resolves the clock once.

## Architecture

```
Domain components        DLFSDrive, Social        one component per path
Application component    ALatticeApplication      root merge and optional region factories
Lattice host             LatticeHost              persistence and publication
Cursor                   path, CAS, drafts        atomic state access
Component and lattice    merge, sync              admission and publication
Optional host runtime    NodeServer               replication and lifecycle
```

`LatticeHost` is the developer-facing host. It always has a store and owns the
root publication policy but not the store lifecycle. A node can host the same root
and installs its replication pipeline during launch; application branches never know
a node exists.

## Designing the lattice

**Start from the data model.** Sketch the tree, for example a social network:

```
:social (owner lattice)
  └── <ownerKey> (SignedData)
        └── Index<Keyword, ACell>            per-user record
              ├── :feed    → Index<Blob, ACell>        posts by timestamp
              ├── :profile → AHashMap<Keyword, ACell>  profile fields
              └── :follows → AHashMap<ACell, ACell>    follow records
```

**Choose merge strategies bottom-up.**

| Primitive | Use when | Empty value | Merge |
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

**Compose structured records from keyed lattices.** Use `KeyedLattice` for named
children with different strategies in an index, or `StringKeyedLattice` for a
string-keyed hash map. These provide per-child merge, navigation and the appropriate
empty value. A custom lattice is needed only for semantics the standard types cannot
express; it must define merge, child lookup and empty value consistently.

**Wrap with an owner lattice** for self-sovereign data:

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

**Compose from a generic host.** Hosted application factories accept the host:

```java
public final class MyApplication extends ALatticeApplication<Index<Keyword, ACell>> {
    private final Social social;
    private final DLFSRegion files;

    private MyApplication(LatticeHost<Index<Keyword, ACell>> host) {
        super(LATTICE, host);
        social = Social.connect(this);
        files = DLFSRegion.connect(resolve(Keyword.intern("documents")), host.store());
    }
    public static MyApplication connect(LatticeHost<Index<Keyword, ACell>> host) {
        return new MyApplication(host);
    }
}

// Local
LatticeHost<Index<Keyword, ACell>> local = LatticeHost.open(store);
MyApplication app = MyApplication.connect(local);
local.configureApplications(List.of(app));

// Hosted by a node: identical application code
var host = LatticeHost.open(store);
MyApplication networked = MyApplication.connect(host);
host.configureApplications(List.of(networked));
var node = new NodeServer<>(host, config);
node.launch();
```

The caller owns and closes the store and the node; components are views and own
neither. A node serves from the host's store so published roots never
reference data the primary store lacks. Publication policy is a lifecycle concern: a
root defaults to local store publication, and network infrastructure installs and
freezes its policy during launch.

**One component, one path.** Each level of the data model is a component holding a
cursor at one path and creating the components one level deeper:

```
Social        cursor at the owner lattice level
  SocialUser  cursor at the per-user record (through the signing boundary)
    Feed      cursor at Index<Blob, ACell>
    Follows   cursor at AHashMap<ACell, ACell>
```

Hosts, applications and domain components are all components. A lightweight
component holds a working cursor; a publication boundary also holds a destination.
Only the host owns host persistence policy; DLFS components additionally
carry a store handed down explicitly from their application, because streamed file
writes checkpoint blobs before publication. The cursor is the only connection back
to application state. There is no second component-parent hierarchy, and cursors
know nothing of storage. The component that owns an owner-keyed or stamped level
wraps that boundary itself, so the boundary is visible exactly where the domain
structure places it and descendants never see it: the social component wraps the
owner's slot, and the feed component never sees a signed envelope.

Long-lived application and region components are fine; owner and session views
should be held by the service that routes them or recreated on demand, never
accumulated in an unbounded cache.

**Write through cursors.** Use read-modify-write updates. The update function
receives the current value, and the component supplies its own initial value within
that same atomic update when the path may be absent:

```java
public void putPost(Blob key, String text) {
    long ts = getContext().currentTimestampValue();
    AHashMap<Keyword, ACell> post = SocialPost.createPost(text, ts);
    cursor.updateAndGet(feed -> (feed == null ? SocialLattice.FEED_LATTICE.zero() : feed)
        .assoc(key, post));
}
```

This example writes an explicitly selected key. The feed's post operation allocates
a free key starting at the operation timestamp, advancing the key on collisions
without changing the post's timestamp. Key allocation is separate from the write
clock.

Reads return null for absent paths. A write through a child the component declared
with its lattice creates the missing level from that lattice's empty value; a plain
path write requires the intermediate containers to exist.

**Keep record construction in static helpers** (`SocialPost.createPost`), separating
data format from cursor mechanics. Rules of thumb:

- Always include `:timestamp` in LWW-merged records; it is the tiebreaker.
- Use `Keyword` keys for record fields and `Blob` keys (big-endian timestamps) for
  ordered collections in an `Index`.
- Use `Index` for lattice-level containers, where child lookup and empty value
  matter, and `AHashMap` for leaf records and dynamic key sets. `Index` resolves
  `Keyword` and `AString` keys identically, which matters for JSON interoperability.

## Application root and host

The application component has working state for a hosted root and declares the
root lattice: it exposes that lattice, merges incoming root values and hands out
typed region cursors. It attaches to a host, which explicitly creates or
opens a local root, or hosts a networked root. Neither merging nor hosting requires
domain components to exist.

The global lattice has a keyed map root, represented as `Index<Keyword, ACell>`.
Applications participate at the globally defined region paths and meanings,
composing the region lattices they understand. Unknown incoming regions are ignored
by keyed merge; existing unknown regions are retained. This is selective
participation, not a separate application namespace. The generic base also supports
other value types for local applications and tests.

There are two complementary kinds of knowledge:

- The supplied root lattice defines region dispatch, navigation, verification and
  merge semantics for the complete root value.
- A domain application knows how to construct components for its regions, possibly
  parameterised by owner, collection name or another path key. Each component can
  construct further components within its own subtree.

Component factories are optional domain conveniences. The base application does
not discover merge rules from components, enumerate them during merge, or require
a component registry. A relay can merge every region in its supplied lattice
without constructing a single domain component.

```java
var host = LatticeHost.create();
var app = new LatticeApplication<>(selectedRegions, host);
host.configureApplications(List.of(app));
host.merge(incomingRoot); // delegates to the application's private working state
app.sync();              // merge working state back to the host
host.sync();             // optional host policy; otherwise a no-op without a store
```

The executable [signed favourites example](../src/test/java/convex/lattice/app/FavouritesApplicationTest.java)
assembles a storeless `LatticeHost.create()` directly with a `FavouritesApp`. The application supplies
the controllable clock and `FavouritesUser` signing components. Each user exposes
a lightweight `Favourites` component through `user.favourites()`, with atomic
`add` and `remove` operations. Each edit signs into the application working state
through a `SignedCursor`. Repeated user lookups create distinct views of that same
state, without per-user working branches or caches. Only `app.sync()` publishes to
the host, which supplies incoming
delegation and persistence without domain-specific wrappers. The tests exercise
Alice and Bob locally, Charlie on another in-memory host, signed whole-record LWW
merges and explicit publication. A separate
[persistence variant](../src/test/java/convex/lattice/app/FavouritesPersistenceTest.java)
tests publication to Etch and reopening the same application.

A host's store is optional. `LatticeHost.create()` starts an empty keyed root with
no store; `LatticeHost.create(initialValue, null)` supports other initial values.
Without a store, `persist` returns its input unchanged and `flush` does nothing.
Host `sync()` still invokes a configured publication policy; otherwise it returns
the current snapshot. The root cursor holds the value directly: there is no
`MemoryStore`, `NullStore`, default-store lookup or thread-local store fallback;
`host.store()` returns `null`. `NodeServer`
requires a host with a cell store for its network and persistence infrastructure.

The [host store tests](../src/test/java/convex/lattice/HostStoresTest.java) cover
these distinct configurations:

| Host store | Default `sync()` | Restoration with `open` |
|---|---|---|
| `null` | Returns the in-memory snapshot | Uses the initial value |
| Explicit `NullStore` | Passes the snapshot to the discarding store | No retained root |
| Explicit `MemoryStore` | Selects the root in that store | Restores from that same live store instance |
| Explicit `EtchStore` | Persists the tree and selects the root | Restores after closing and reopening the file |

`persist()` stores cells without changing either the host cursor or the retained
store root. `flush()` delegates to an explicitly supplied store's durability
barrier. The caller owns and closes the store; the host does not own its lifecycle.
A custom publication policy replaces the default store-root publication step.

Opening a host restores the retained root, or starts at an empty keyed index
if the store has none. Explicit initial-value overloads support non-map local roots. Creating a host deliberately starts a fresh root; its first
publication replaces any retained root. Applications accept a host rather than a
store, so this choice belongs to host assembly, and the caller owns the store
lifecycle. Each application supplies its own lattice.
Applications may also override `merge` to delegate to components with their own
merge strategies and discard input they do not accept; verification must still use
the owner-bound rules for the paths they publish. Merging keeps the current local
value in the `own` position and does not publish or flush.

### Incoming delegation

Configure incoming handling once, before launching a node. For a shared root with
one admission policy, one live application is enough:

```java
var incoming = LatticeApplication.live(rootLattice, host);
host.configureApplications(List.of(incoming));
host.merge(incomingRoot); // verifies and merges into the shared root once
```

Domain components can use live paths in this root or fork their own paths. There
is no requirement to register each domain application as an incoming handler.
Observing accepted root changes and choosing when to publish private work are
application policy. The Social + P2P + DLFS example uses this arrangement.

The host also supports multiple explicitly configured incoming handlers when
independent admission policies are needed. Such a handler can route a signed
subtree to a signed component, which verifies its envelope before merging its
payload into unsigned working state. Discarded input stays discarded. Enclosing
signed envelopes must be verified before extracting and routing interior values.

Delivery is not a transaction across applications: if a later application fails,
earlier merges remain, and lattice delivery is safe to retry. The returned root may
be unchanged until applications sync. Both an explicitly empty application list and an unconfigured host ignore all incoming
state. For a relay, configure `LatticeApplication.live(lattice, host)`: this application
accepts the supplied lattice directly into shared host state.

A node routes incoming pushes, whole-root and path pulls, direct merge calls and
explicit snapshot candidates through the host. Delivery does not invoke
application sync; host sync and shutdown publish only state already installed in the
host. An acknowledgement confirms delivery, not publication of private work, and an
application may ignore a value without reporting an error.

Path-addressed delivery forwards the value unchanged to each application. The host
does not use its lattice to infer application interests. The base application
treats an empty path as a whole-root merge and ignores every non-empty path; an
application overrides this to choose its interests, validate input and delegate to
domain components, verifying enclosing signed envelopes before accepting interior
values. The standard `LatticeApplication` explicitly accepts paths declared by its lattice,
rejecting paths through signed envelopes: callers must supply the complete envelope
at its slot. The host never interprets paths.

A node wraps a `LatticeHost` and delegates incoming merges to it. Assemble custom
applications before starting the node:

```java
var host = LatticeHost.open(store);
var app = new MyApplication(host);
host.configureApplications(List.of(app));
var node = new NodeServer<>(host, config);
node.launch();
// Later, according to application policy:
app.sync();
host.sync();
```

The supplied host is already initialised; the node never reloads it. Launch installs
the node's publication pipeline, so host publication policy must not already be
frozen. Node startup does not invoke application sync. Transport and propagation
configuration remain explicit.

For simple relay usage, `new NodeServer<>(lattice, store, config)` creates a host
and installs a standard live `LatticeApplication`. At launch it merges retained
state through that application, preserving pre-launch local edits. Custom applications
use the explicit assembly above; there is no application-factory launch overload.

### Sharing a host through component paths

Choose the publication boundary for each part of an application. A live domain
view shares its path in the host. A fork stages just that component's subtree:

```java
var liveSocial = Social.connect(host, socialContext);
var social = liveSocial.fork();
var liveFiles = DLFSRegion.connect(host.cursor(), host.store(), Keywords.FS);
liveFiles.setContext(filesContext);
var files = liveFiles.fork();
var peer = P2PUser.create(host.cursor(), ownerKey, p2pContext);

social.user(ownerKey).feed().post("A staged post");
peer.node().setNodeInfo(nodeInfo); // immediately updates its shared P2P path
social.sync();                   // publishes only :social, preserving :fs and :p2p
files.sync();                    // publishes only :fs, preserving :social and :p2p
host.sync();                     // persistence and propagation are separate
```

Social and Files can be grouped into ordinary application classes that choose
when to call these operations. They need no common private root, combined
publication lattice, multi-region projection, or special sibling-preservation
merge. P2P views obtained independently by both applications see the same state.
The application objects retain cursors and domain components, not parent links.

An independent component knows only its working storage, destination cursor and
own lattice and policy. Its destination can be a path inside any application tree.
Publication updates that cursor and stops; the cursor provider controls what
happens above that boundary. Components supply construction of missing containers;
raw path cursors themselves do not construct them.

Publication captures one working snapshot. If the destination is unchanged since
the last publication, the snapshot is installed directly. Otherwise it is merged
with the destination, with local edits as `own`. Equal-timestamp local edits retain
that precedence; an unchanged working value adopts the destination. Edits made
during publication remain pending for the next sync. A live view needs no sync to
observe root changes; a staged component reconciles with them when its policy calls
sync. That operation both reconciles and publishes, rather than being a read-only
refresh.

A signed merge needs no signing capability when its result equals either input's
payload: it reuses that envelope. A result different from both payloads requires an
authorised signer. The shared example lets the root accept an expanded remote
signed feed, then lets the owner component combine that accepted state with its
private posts and sign the new result. A signer-free root cannot replace explicit
owner reconciliation for arbitrary divergent signed inputs; applications needing
that behaviour must route the conflicting envelopes to the owning component.

Sequential syncs of separate paths are not atomic together. For a consistent read
of several published paths, capture the immutable host root once and extract each
path from that value. This does not include unpublished state in independent forks.
An operation requiring atomic changes across paths must explicitly use a common
ancestor transaction boundary.

The existing `ALatticeApplication(lattice, host)` constructor stages the whole
root. Use it only when that entire publication boundary is intended; it is not the
composition mechanism for several applications interested in different regions.
For shared applications, stage their domain paths instead. The examples demonstrate
this with the existing component machinery, without changing cursor semantics.

These policies are not an access-control boundary between code sharing a process.
Verification remains bound to the lattice instances, configured before use.

### Batched signing and application preparation

For immediate signed edits, a lightweight component wraps its destination with
`SignedCursor.create(slot, key)`. It needs no private working state or separate sync.

Deferred signing is an explicit choice: `SignedComponent` owns a private unsigned
working cursor and a signed destination. Bind it to the owner-bound signed lattice
at that host path, so it
carries the owner verifier. Domain components use its working cursor through their
rebinding factories and update normally, without signing each edit:

```java
var signed = new SignedComponent<>(owners.signingFor(owner), signedSlot);
signed.setContext(ownerContext);
var user = existingUser.withCursor(signed.cursor());
user.feed().post("First post");
user.feed().post("Second post");
signed.sync(); // one signed publication to signedSlot
```

On sync, the component merges the unsigned payload with the destination, signs the
result once and publishes the envelope. Unchanged payloads do not sign. A signing
failure leaves the destination unchanged, and sync is not a rollback transaction:
a completed publication stands even if later local reconciliation fails. Untrusted
envelopes enter through verified merge; publication reconciles only values the
application has already admitted.

Applications choose their components and publication order explicitly:

```java
public void sync() {
    socialOwner.sync();
    nodeOwner.sync();
}
```

There is no component registry, parent-component link, scheduler or hidden worker.
This is not an atomic transaction across independently editable component roots:
a completed publication stands if a later one fails. The notebook example publishes
its signed component directly to the host path, without an enclosing whole-root
fork or a second signed reconciliation hook.

An application deliberately using the whole-root staging constructor can still
use `beforeSync()` to prepare children before publishing that enclosing snapshot.
That is an explicit additional boundary, not required application scaffolding.

## Component transactions

A transactional component (`TransactionalComponent`) adds typed transaction
operations, synchronous, asynchronous and locked. Social, feed, follow, identity,
node-record and DLFS region and drive components implement it, and existing
component subclasses can opt in without changing the component base.

```java
user.transact(u -> {
    u.feed().post("Hello");
    u.follows().follow(otherDid);
});

user.transactAsync(u -> u.feed().post("Another post"))
    .thenRun(this::notifyLocalCommit);
```

The callback receives a private component view. Its child components share the same
private state, and the final value is installed atomically through the live cursor's
signing and stamping boundaries. An exception discards the staged state. Nested
transactions on that view remain part of the outer transaction. Views and
descendants must not escape the callback; state access after completion or from
another thread is rejected. A raw cursor sync on a draft is rejected; a lightweight
component's sync has no publication work and leaves the draft local.

Callbacks may repeat and must restrict their mutations to the supplied view.
External effects belong after synchronous completion or in a future continuation.
The transaction protects only its subtree: decisions must not depend on reads from
captured live components outside it. Put such operations at their common ancestor
instead. Persistence may write immutable cells while staging; rollback does not undo
those physical writes or any external effects. Two concurrent increments both take
effect: a concurrent writer installing the same proposed result does not count as
completing this operation.

The host selects the concurrency policy at construction:

| Root | `transact` | `transactLocked` | Ordinary writes |
|---|---|---|---|
| Ordinary root (default) | Optimistic retries | Throws before invoking the callback | Original atomic operations |
| Transactional root | Optimistic, with serial fallback under contention | Callback runs once under root exclusion | Commits coordinate through a root lock |

```java
// When creating a node:
NodeConfig config = NodeConfig.create().withTransactionalRoot(true);
```

The lock belongs to the shared root, covering every application view, ordinary
cursor write and incoming merge. It excludes writes to unrelated regions too; reads
continue to observe immutable committed values. Locked callbacks must stay short and
must not wait for another writer, publish state, or mutate a captured live view. An
ordinary root never acquires a write lock implicitly.

For a custom component, implement the transactional interface with a rebinding
factory that creates a fresh component with the same domain metadata and resolved
context over a supplied cursor. Rebuild children against that cursor; do not reuse
cached live children. A component owning configuration, such as a DLFS store, passes
that along itself.

Completion of a transaction means committed locally, not persisted, published or
acknowledged by peers. Components create no workers of their own. Publication and
durability remain explicit operations.

## Fork and sync

A component fork is an independent working copy; its sync merges it into the
destination it was forked from with lattice semantics. Concurrent state is merged
rather than treated as a transaction conflict; verification, signing or storage can
still fail. This is separate from publication and durability:

```java
Social forked = social.fork();
forked.user(myKey).feed().post("Post 1");
forked.user(myKey).feed().post("Post 2");
forked.sync();   // merge into the enclosing working component
app.sync();      // checkpoint owners and publish to host
host.sync();     // persist and propagate the hosted root
host.flush();    // optional physical durability barrier, a host operation
```

Domain components expose a fork with their exact type when batching is a use case;
sync is inherited from the component base.

| Operation | Meaning | Moves a cursor? |
|---|---|---|
| Host persist | Write the current root's reachable cells to the host store; return the store-backed value | No |
| Component sync | Publish into its destination; a lightweight view has no publication work | Yes, for an independent working boundary |
| Host sync | Run host persistence and replication policy on one snapshot | May install persisted references; concurrent edits remain pending |
| Host flush | The store's physical durability barrier | No |

Incremental writers with their own store, such as DLFS drives, checkpoint at
intervals to swap direct references for store-backed soft references and relieve
memory pressure; they install the returned value themselves. Persistence neither
selects a retained root nor licenses garbage collection.

### Persistence

Persistence lives in two places and nowhere else:

- The host owns the host store. It publishes the root on sync, can
  persist reachable cells without publishing, and provides flush, the physical
  durability barrier. Working applications publish into this host before its own
  explicit sync persists or propagates their state.
- DLFS components carry a store explicitly, because streamed file writes checkpoint
  blob branches before anything is published. The DLFS application takes the host
  store at construction and hands it to regions, drive maps and drives; forks and
  transaction views keep the same store. A drive without a store keeps file data on
  the heap until the root is published.

Cursors have no persistence API. Components own publication; the host additionally
owns its persistence and durability policy. With incremental checkpoints, a
transaction rollback or an abandoned fork may leave immutable cells in the store;
they are unreferenced and reclaimable, never a logical state change. Forking below
an owner's signing boundary still defers that signature until merge-back, so a DLFS
fork can make many edits, checkpointing blobs as it goes, and sign once on sync.

## Cursor boundary

Cursors provide atomic access, navigation and isolated drafts. They carry no
context, perform no lattice merges and construct no containers of their own. A raw
cursor fork is explicit overwrite-on-sync; components that need reconciliation use a
publication boundary instead. A plain path write requires its intermediate
containers to exist, and update callbacks receive `null` for absent values. The
component owning a level supplies what the cursor lacks: its lattice's empty value
when creating a child position, and explicitly bound signing or stamping functions
when wrapping a boundary. Batched signing belongs to the signed component. The
cursor mechanics are described in [LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md).

## Write policy: `LatticeContext`

The context supplies signing capabilities and the write clock; typed ownership
lattices bind owner verification rules to the lattice instance. Install a context on
the application component for application-local policy, or on the host for
construction defaults. Child components resolve policy when constructed; later
parent policy replacement does not alter existing children. Cursors have no context.
Transaction views retain the component policy when rebound. A fixed context suits
deterministic tests; an application policy resolves dynamically from a clock, wallet
or key store and must be thread-safe if shared. The `with...` methods override one
capability, such as the timestamp, signing key, owner verifier or future-skew
tolerance, and delegate the rest.

```java
component.setContext(LatticeContext.create(null, myKeyPair));                  // runtime clock
component.setContext(LatticeContext.create(CVMLong.create(1000), myKeyPair));  // fixed clock
```

**Time.** The current timestamp is resolved once per logical write, even under
retry, and components consume it exactly: no incrementing, no comparing with stored
values to invent a later one, no hidden logical clock. Reusing a timestamp
deliberately creates a tie, which the local (`own`) operand wins. Where ordering
across replicas matters, especially delete followed by recreate, the application
supplies timestamps that express it.

**Signing.** Signing as an owner is the single authorship rule and is exactly what
owner verification checks on merge. An `AccountKey` owner requires that key; an
indirect owner (address, DID) is resolved by the owner verifier, lenient when none
is installed. A write the policy cannot author fails, so a slot no peer would accept
never reaches local state.

## Security model

An owner lattice maps owner keys to signed envelopes and, on merge, rejects any value
whose signer does not match the owner. The same rule runs when a value is authored:
a signed write for an owner the context cannot sign for fails and stores nothing. A
merge that would have to synthesise a value for an owner this node cannot sign for
keeps its own value instead; merging validly signed data never fails and never
attaches a non-owner signature.

Local state is trusted in the sense that matters: nothing polices what an owner
writes into their own slot, only that slots are written in a form peers accept.
Write adversarial tests that construct forged state at the raw data level and assert
that the owner lattice's merge drops it.

## Owner identity policies

**Design decision:** verification rules belong to lattice instances. Different paths
can compose different rules, including different rules for the same owner identity.
Navigating to a child or deriving a restricted interest view preserves the applicable
verification boundaries; neither operation substitutes a global rule.

An owner need not be a public key. A region can map DID strings to signed envelopes
and bind the rule that authorises a signing key for each DID to its owner lattice.
Ownership machinery is shared in an abstract owner lattice (`AOwnerLattice`), with
concrete types selecting the container and key conventions; both hash-map and index
variants support custom owner verification, and the index variant uses the existing
`Index` value type.

```java
// K is the owner identity type; V is the unsigned payload type.
public abstract class AOwnerLattice<K extends ACell, V extends ACell, M extends AMap<K, SignedData<V>>>
    extends ALattice<M> { /* ... */ }

public class OwnerMapLattice<K extends ACell, V extends ACell>
    extends AOwnerLattice<K, V, AHashMap<K, SignedData<V>>> { /* ... */ }

public class OwnerIndexLattice<K extends ABlobLike<?>, V extends ACell>
    extends AOwnerLattice<K, V, Index<K, SignedData<V>>> { /* ... */ }

public final class OwnerKeyLattice<V extends ACell>
    extends OwnerIndexLattice<AccountKey, V> { /* ... */ }

// The predicate is part of this region's ownership policy.
OwnerMapLattice<AString, MyState> identities = OwnerMapLattice.create(
    stateLattice,
    (owner, signingKey) -> didAuthority.isAuthorised(owner, signingKey));

// Direct public-key ownership with an Index-backed container.
OwnerKeyLattice<MyState> keys = OwnerKeyLattice.create(stateLattice);
```

The predicate answers whether a key may author this owner's subtree, allowing
multiple authorised keys without reducing every identity to one public key. DID
resolution is an implementation detail of the supplied authority service; the signed
lattice still verifies the cryptographic signature separately. The public-key
variant fixes authorisation to owner-key equality with the signing key and needs no
predicate. Container choice is independent of ownership policy, and migrating an
existing hash-map region to the typed variant preserves its stored representation.
The original `OwnerLattice` remains a compatibility API.

The bound predicate is authoritative for owner-to-signer matching in this region;
an application context cannot replace it. Nested ownership boundaries each retain
their own rules; a child rule does not waive an enclosing rule. The context
continues to provide the available signing capability and write clock. Host
admission policy may independently decline to adopt data. A predicate rejection or
unresolved identity cannot fall back to accepting an arbitrary signer.

The same ownership rule applies to both merge forms, to merges directly at an
owner's signed child, and to local authoring below the signing boundary. Authoring
still requires an available authorised signer; the predicate grants no signing
capability and need not select a private key.

For stateful DID methods, resolve and refresh authority information outside atomic
update retry loops. Verification uses a consistent local authority view for one
operation; unknown resolution must not cause unbounded network work in merge.
Changing that view governs subsequent incoming claims; it does not automatically
erase previously accepted data or define historical-signature validity.

The compatibility owner lattice still uses the context's owner verifier on
context-aware merge, including its lenient fallback for indirect owners. The typed
variants require an explicit bound predicate, except the public-key variant, and do
not consult the context verifier. Applications are not migrated automatically. The
long-term design retires context-wide owner verification after these callers
migrate. CAD038 needs to describe the instance-bound policy option and reconcile its
safe-default wording with its documented legacy lenient fallback. DID method and
account authority details follow CAD043 and CAD004.

## Deletes

Additive merges union keys, so a removed key returns when an older replica merges
back. Under index, map or set lattices, delete by tombstone: set a `:deleted` field
and bump `:timestamp` so the deletion wins.

For durable deletes, model the region as a whole-value LWW leaf over JSON-style
navigation, and let the owning component stamp each local write:

```java
ALattice<ACell> state = LWWLattice.create(JSONLattice.INSTANCE, tsFn);

// The component owning the leaf wraps it in a stamped cursor before navigating below.
AForkableCursor<ACell> leaf = StampedCursor.create(resolve(KEY_STATE),
    getContext()::currentTimestamp,
    (value, timestamp) -> RT.ensureMap(value).assoc(KEY_TIMESTAMP, timestamp));
```

`JSONLattice` provides recursive navigation below the leaf. `LWWLattice` merges the
whole value by timestamp, so a smaller map with a newer timestamp replaces the old
one. The stamped cursor timestamps each changed local write. A delete is then a
read-modify-write that dissociates a key. Missing maps or vectors below the leaf
are constructed by the component's write; navigation does not infer container types.

## Proposed lifecycle and subscriptions

**Design proposal.** The root merge API and ownership hierarchy are implemented;
the lifecycle and subscription signatures below remain proposals. This design uses
CAD024 for merge semantics, CAD035 for cursor atomicity, CAD036 for hosting, CAD038
for ownership and CAD045 for application composition. Changes to their application
contracts need corresponding CAD updates before the implementation is described as
normative.

### Responsibilities

An application remains a typed component at the complete root. Its supplied lattice
is sufficient for root merging; domain subclasses add component factories. Lifecycle
and subscriptions manage active work and replication demand without becoming another
source of merge semantics.

| Object | Responsibility |
|---|---|
| Lattice host | Shared authoritative cursor, schema, storage and host services |
| Application component | Root merge API; optionally domain factories, lifecycle and subscriptions |
| Subscription | Interest in one subtree, a live cursor and optional change observers |
| Domain component | Lightweight domain API around a cursor at one path |

The host remains the host abstraction. A local root supports the same
application API as a root hosted by a node; the node installs replication services.
A new host interface is only needed if another implementation requires it.
Application-selected region definitions configure the host root at construction;
the host does not need to know the corresponding component classes. Combining
different applications' region selections in a shared host remains future work.

### Basic hosted setup

A basic installation should require the caller to supply the application setup, a
store and optional network settings. Application implementations supply their
lattice definitions and domain component construction. Standard setup should
assemble the host, incoming delegation and ordinary propagation machinery
internally; callers should not need to wire listeners, propagators or publication
callbacks for this case.

Setup must restore the retained root before constructing applications with private
working state, configure their incoming delegation before accepting traffic, and
start transports last. Shutdown stops incoming traffic, allows application-owned
shutdown policy to complete, publishes and flushes the host's already-published
application state, then releases owned resources. Host shutdown must not force
private application drafts to publish. A caller-supplied store remains caller-owned,
and the host remains explicit and accessible.

The default local setup creates no network workers. Networking, where requested,
has an explicit lifecycle owner. Advanced callers can still supply their own
propagation groups, transport and publication timing without introducing parent
links or resource ownership into lightweight components.

**Design decision:** applications always initiate their own publication. Incoming
delivery invokes application merge, without an automatic application sync or
preparation hook. An application may choose immediate publication in its event
handling, batch changes, or wait for an explicit user action. These are application
semantics, not eager or deferred modes imposed by the host.

Application sync publishes into its destination cursor and stops there. Host
publication can persist and propagate only state already published into that host;
it must not pull private working state from applications. Standard setup may wire
host publication policy, but must not infer that receiving a value permits
publishing an application's pending edits. Ordinary cursor updates never trigger
enclosing component publication. A successful incoming delivery therefore does not
imply that the application has published, persisted or rebroadcast the value.

Signing establishes responsibility for the entire signed subtree. Its owner is
responsible for merging and publishing that tree, including coordinating multiple
writers using the same signing identity. Preserve the existing timestamp and
own-value tie rules. The API does not introduce a hidden logical clock or require
non-owner peers to synthesise signed values. Timestamped replicated registers should
be enclosed by an appropriate signing boundary.

### API sketch

These signatures show the proposed public surface; they are not compilable examples
against the current implementation. Paths use canonical CVM keys.

```java
public abstract class ALatticeApplication<V extends ACell>
        extends ALatticeComponent<V> implements AutoCloseable {
    protected ALatticeApplication(ALattice<V> lattice, LatticeHost<V> host);

    public final ALattice<V> getLattice();
    public V merge(V other);

    public final void launch();
    public final void close();

    public final <T extends ACell> LatticeSubscription<T> subscribe(ACell... path);
    public V sync();
    public final CompletionStage<Void> pull();

    protected abstract void onLaunch();
    protected void onClose();
    protected LatticeContext writeContext(LatticeContext inherited);
    protected void onError(Throwable failure);
}

public interface LatticeSubscription<V extends ACell> extends AutoCloseable {
    AForkableCursor<V> cursor();
    AutoCloseable watch(Consumer<V> observer);
    void close();
}
```

The schema and domain factory establish a subscription's value type, not inference
from a raw path; domain factories should hide this unchecked boundary from ordinary
callers. Unknown schema paths fail at subscription creation; an absent value at a
known path is valid. The default write context inherits host policy; an override
supplies the application's write policy before binding cursors. The default error
handler reports asynchronous failures through logging.

```java
public final class MyApplication extends ALatticeApplication<Index<Keyword, ACell>> {
    private Feed feed;

    public MyApplication(LatticeHost<Index<Keyword, ACell>> host) {
        super(LATTICE, host);
    }

    @Override
    protected void onLaunch() {
        LatticeSubscription<Index<Blob, ACell>> posts = subscribe(FEED_PATH);
        feed = Feed.connect(posts.cursor());
        posts.watch(this::refreshTimeline);
    }
}

MyApplication app = new MyApplication(node.getHost());
app.launch();  // node already launched and configured
// The same application can launch against a standalone LatticeHost.
```

The feed factory above is a proposed cursor-based component factory. A lightweight
component holds a working cursor, with no component parent or host link. The one
component family that needs a store before publication, DLFS, is handed the host
store explicitly by its application. Component creation opens no connections,
starts no workers and installs no observers.

### Launch and close

- Host schema and peer groups are configured before host launch. Application launch
  uses its bound, ready host, binds its policy and runs the launch hook. Readiness
  means locally usable; it does not mean every remote peer has been contacted.
- Application launch is single-use. If the launch hook fails, release registrations
  made during launch and leave the host and other applications running.
- Subscribing is valid during launch and while running. It establishes a local view
  immediately and requests replication through the host's configured services. It
  does not wait for network acquisition. Local-only hosts create the same view
  without network activity.
- Application policy supplies signing and timestamp capabilities before writes
  begin. Merge authorisation is bound to the application's lattice instances; an
  application's signing capability does not replace it.
- Close is idempotent. It stops accepting subscriptions, invokes the close hook, and
  releases all application registrations even if cleanup fails. It does not close
  the shared host, store or peer groups. Subscriptions can also close individually.
- Closing a subscription releases its interest and observers; it does not erase
  state or revoke already-created cursor views. Components remain ordinary views,
  and their owner is responsible for ceasing work before application shutdown.

### Interests as restricted lattice views

A keyed lattice is a **typed interest set**: the declared keys select the children
in which it participates, and each child defines how that selected data merges. A
keyed lattice expresses a finite set; map and index lattices express a family of
keys sharing child semantics. Compose these for structured records instead of
implementing a custom merge for each record.

Runtime subscriptions select from the host's existing lattice tree:

- An empty path selects the whole configured lattice. A non-empty path selects
  that subtree, including future descendants admitted by its schema.
- Several subscriptions form the union of their selections. An ancestor interest
  covers descendant interests for replication, while each handle retains its own
  observation path. Overlapping interests across applications are reference-counted.
- The host schema provides one merge meaning per path. Subscriptions cannot install
  competing merge functions at the same path. Schema composition must reject
  conflicting definitions explicitly; subscription creation does not extend schema.
- Interest is demand for acquisition and continued replication, not permission to
  read, write or publish. Existing peer-group admission and disclosure policies
  remain authoritative. Demand is shared only across compatible configured groups.
- Closing the last interest permits replication work to stop. It neither deletes
  retained data nor makes it eligible for collection independently of store policy.

This allows a subscription trie to produce a restricted lattice view internally,
without requiring application writers to construct another lattice or understand
propagator filters. Start with subtree paths and unions; arbitrary predicates and
wildcards are not required for the first API.

**Merge selection and projection are different operations.** A keyed merge ignores
unknown incoming keys but retains unknown keys already present in `own`. Preserve
that non-destructive merge behaviour. A restricted serving or observation view must
explicitly project selected data; it cannot assume that merging with a keyed lattice
removes everything else. Absence in a partial view is never a deletion instruction.

Selection must also respect indivisible merge and authentication boundaries. A
subscription may observe any navigable sub-path, including inside a whole-value LWW
register or signed tree. Replication acquires and merges the enclosing complete
value when required, then exposes the requested sub-path. It must not truncate a
signed value, sign a projection on the owner's behalf, or treat an LWW field as an
independent register. If group policy prohibits the necessary enclosing value,
replication cannot satisfy that interest and must report the limitation.

### Observation contract

Subscribing establishes interest; watching optionally observes the resulting state.
Closing a watch leaves the subscription active. Each watch receives an initial
snapshot, followed by changed values at its path. Absence is represented by a null
value, not by a missing initial notification. The immutable CVM value is the
snapshot; no public snapshot wrapper or revision token is required.

Registration captures the initial value and establishes subsequent delivery without
a gap in which a committed update can be missed. Notifications cover local atomic
writes, fork merges into the live tree and accepted remote merges. An ancestor
replacement also notifies a descendant watch if its value changes. A no-op merge
does not.

Delivery is asynchronous, serial per watch and follows local installation order.
Pending states may coalesce to the latest snapshot, with bounded pending work per
watch; callbacks are state observation, not a durable event log. Initial delivery
precedes subsequent delivery. A slow or failing callback does not block state
installation or another watch; failures are reported through the application's
error handler. Watch close prevents further callback dispatch, though a callback
already running may finish. Fork-local changes remain private until merged into the
live tree.

### Atomic operations and synchronisation

Components use the existing cursor atomic operations. Views at overlapping paths
share the same authoritative state, so concurrent updates retry against the latest
enclosing value instead of replacing it with an earlier snapshot. Update functions
must be pure and may run more than once. IDs and externally visible effects must not
be generated inside a retryable update function. One logical write resolves the
context timestamp once and reuses it across retries.

Whole-value LWW is an explicit schema choice. A deep update inside such a value
must atomically read and modify its latest enclosing value, stamp the completed
write and cross any signing boundaries before installation. Two local components
changing different fields then preserve both changes. Never implement this as
get, modify, then set. Across replicas or detached forks, the selected LWW value
still wins as a whole; local atomicity does not introduce field-wise merge semantics.

| Operation | Completion contract |
|---|---|
| Cursor update | One local state change is atomically visible; publication may still be pending |
| Application merge | Merge an incoming root using the supplied lattice and component context; publication remains separate |
| Fork sync | Merge one working snapshot into its immediate parent; concurrent state follows the declared lattice semantics |
| Application sync | Run the preparation hook, then publish one application snapshot through its cursor boundary |
| Host sync | Persist and publish a host root snapshot, then notify configured replication services; no remote acknowledgement |
| Application pull | One pull round for a captured set of current interests and configured routes has merged and published its successful responses; failures complete the stage exceptionally, without undoing successful merges |
| Host flush | Complete the host store's physical durability barrier; sync first to include pending working state |

Pull is a local no-op without replication services. Configured replication with no
usable route reports unavailability rather than claiming remote synchronisation.
Ongoing replication operates while an interest is held; pull provides an explicit
completion signal, not a claim that all peers are globally up to date. Host sync
publishes all applications' already-installed writes because the host has one
authoritative root. A root snapshot already published cannot be rolled back by a
later propagation failure.

Atomic multi-path operations use one update or transaction at a common ancestor.
Separate component calls are separate atomic operations. A fork is a mergeable
batch, not serialisable isolation for arbitrary cross-field invariants.

## Testing

Application tests are grouped by the boundary they exercise:

- [`convex.lattice.app`](../src/test/java/convex/lattice/app) in `convex-core`:
  complete local applications in memory or with a store, including restoration,
  interest selection, atomic updates, signing and publication. No node or network
  is needed for local end-to-end coverage.
- [`convex.node.app`](../../convex-peer/src/test/java/convex/node/app) in
  `convex-peer`: application hosting, network delivery and propagation, using the
  public node APIs. Package-private node checks remain in `convex.node`.
- Domain module tests cover their own components; combinations such as Social +
  P2P alongside DLFS + P2P remain in `convex-integration`, which can depend on all
  those modules.

Keep motivating scenarios small and explicit: construct, edit, merge, publish and
assert the visible state at each boundary. Keep detailed contention and transport
machinery in dedicated regression tests.

The [favourites application](../src/test/java/convex/lattice/app/FavouritesExample.java)
provides the shared domain model for in-memory LWW and separate persistence tests.
[`ApplicationPublicationTest`](../src/test/java/convex/lattice/app/ApplicationPublicationTest.java)
shows lightweight notes inside an explicitly staged, signed notebook. Application
sync publishes the notebook directly to its host path, with no duplicate root fork.
[`TaskBoardApplicationTest`](../src/test/java/convex/lattice/app/TaskBoardApplicationTest.java)
uses domain operations in transactions, including an asynchronous completion that
publishes the application.

The [reading-list network example](../../convex-peer/src/test/java/convex/node/app/ReadingListApplicationTest.java)
assembles applications before node launch and delivers published recommendations
over a real connection. The cross-module
[SharedApplicationsTest](../../convex-integration/src/test/java/convex/lattice/SharedApplicationsTest.java)
assembles Social + P2P and DLFS + P2P on the same host, with one incoming root
application, Etch root and serving stores, and a real loopback propagator. Social
and Files stage their own paths; P2P stays live. It verifies remote root observation,
owner-signed reconciliation of pending edits, sibling preservation and reopening
persisted data.

Class contracts, CAS interleavings, signing counts and transaction fallback tests
remain in `convex.lattice`; transport and launch failure tests remain in
`convex.node`. Cross-domain concurrency regressions are kept separately in
`SharedApplicationInternalsTest`.

## Checklist

- [ ] Design the state tree, then choose a merge strategy per leaf.
- [ ] Compose lattice primitives bottom-up; use keyed lattices for structured
      records and reserve custom lattices for new merge semantics.
- [ ] Wrap with an owner lattice for per-user ownership; register under a root keyword.
- [ ] Supply the root lattice to an application component; optionally expose
      domain component factories at known paths. Root merge requires no components.
- [ ] Accept a host in factories; provide a local open and a host-neutral
      connect.
- [ ] Write with read-modify-write updates, declare child levels with their lattice,
      return exact types from fork.
- [ ] Treat persist, sync and flush as distinct boundaries.
- [ ] Include `:timestamp` in LWW records; tombstone under union merges.
- [ ] Write standalone, connected, fork/sync, multi-user and adversarial tests.

## Where the code lives

- `convex.lattice.ALatticeApplication`, `ALatticeComponent`, `LatticeHost`,
  `SignedComponent` — the component layer.
- `convex.lattice.LatticeContext`, `OwnerLattice`, `SignedLattice`,
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
