# Lattice Application Patterns

How to build an application on the Convex Data Lattice: choose the data and merge rules,
compose typed components over cursors, and connect it to a local store or a
hosting node without the application knowing which. The normative reference is
[CAD045](https://docs.convex.world/docs/cad/lattice_apps); this document is the
practical companion for `convex-core`, with `convex-social`, `convex-dlfs` and
`convex-p2p` as worked examples. Cursor internals are in
[LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md).

## The design from first principles

**The problem.** Many nodes hold copies of shared data and update them
independently. No central authority orders the writes. Any node must be able to
combine any two versions of the data into one, deterministically, so that all copies
converge whatever the order or repetition of exchange. Users own parts of that data,
and nobody else may forge changes to them.

**State is an immutable value.** The whole data tree is one immutable CVM value:
maps, indexes, vectors, sets and signed envelopes. Changing state means producing a
new value and swapping a reference to it. Nothing is mutated in place.

**A lattice is the combining rule.** For each position in the tree, a lattice says
how two versions of the value there combine. The merge is associative, idempotent
and commutative, with one documented exception: on an exact tie the value already
held locally is kept. These properties make convergence independent of order. A
lattice also knows the empty value at its position and the rule for each child key,
so the lattice for a whole tree is composed bottom-up from a few primitives: a
register where the newer timestamp wins, collections that take the union of keys and
merge each entry by its own rule, records with named children, and owner namespaces.
Design the data tree first, choose a rule for each leaf, and compose. Write a custom
merge only for semantics the primitives cannot express. Lattices are pure functions
over values and know nothing about cursors, components or storage.

**Ownership is a lattice rule.** Self-sovereign data lives in regions keyed by owner
identity, each slot holding a signed envelope. The merge rule at that level checks
that the signer is authorised for the owner, by key equality or by a predicate bound
to that lattice instance, and silently drops anything else. The same rule applies
when the local node writes: a write into an owner's slot must be signed by an
authorised key or it fails, so local state never contains something peers would
reject. Authorisation belongs to the schema at that path, and a child rule never
waives an enclosing one.

**Policy is separate from mechanism.** A signed component receives a signer
function; a stamped component supplies a clock separately. The component chooses
its capabilities, possibly resolving keys from a wallet when called. One timestamp
is used exactly as given per logical write. Reusing a timestamp is a deliberate
tie, which the local value wins. Policy is held by the component doing the work,
not by the data or storage.

**Cursors are the mutable layer, and only that.** A cursor is a reference to one
immutable value. It offers atomic update, compare-and-set, navigation into
sub-positions whose writes rebuild the enclosing value atomically, and private
drafts for multi-step transactions. A cursor knows nothing about lattices,
ownership, policy or persistence. It gives atomicity and navigation; meaning comes
from whoever holds it.

**Components give positions their meaning.** A component holds one cursor at one
path together with everything the cursor does not know: the merge rules at that level,
the policy, and the domain operations such as post, follow or create drive. One
component per level of the data model. What a component is follows from a small set
of decisions made when it is constructed:

| Decision | Choices |
|---|---|
| Position | A path cursor handed down by the component that owns the level above. An application's position is the host root, handed to it by the host. |
| Navigation | A component creates children at paths below its cursor, supplying known empty container types when needed. A supplied lattice can optionally provide these types. Boundaries are placed by the owning component, never discovered by navigation. |
| Publication | **Live**: writes go straight through the cursor and there is nothing to publish. **Staged**: a private working copy and a destination; sync reconciles the copy with whatever the destination now holds and publishes the result. A fork is a staged component constructed at the same position. |
| Boundary | For a level the component owns: none, stamp each write, sign each write, or sign once at publication. Signing once needs a staged component, and is the main reason to stage. |
| Reconciliation | The lattice merge at that position, with local edits winning ties, applied only when both the copy and its destination changed since they were last reconciled. A keyed lattice publishes only the keys it declares; the rest adopt the destination. |
| Policy | The context, clock and signers, resolved when the component is constructed. Later changes to the parent's policy do not reach it. |
| Incoming values | Whether the component is configured on the host to receive incoming values, and how it merges or routes what it accepts. Only a component at the root can be configured directly. |
| Transactions | Whether it offers atomic multi-step operations, by supplying a factory that rebuilds it over a draft cursor. |
| Store | Whether it carries a store for checkpoints before publication, as file components do. |

Neither navigation nor reconciliation requires an `ALattice` object: components
can supply container types and merge behaviour directly. The signed component is the
archetype of the staged choice: an unsigned working copy, a signed destination, one
signature at publication however many edits preceded it.

**Compatibility checks are explicit.** `component.checkValue(ACell)` answers
“is this value compatible with this component?”, returning `null` or an `AString`
error reason. Validity is application-defined. The base implementation imposes no
constraints; applications may make this check less strict than merge acceptance.
For example, signed components check envelope shape by default, while merge still
verifies signatures, owner authorisation and payload acceptance. A successful check
is never evidence of authority or a promise that merge will accept the candidate.

Candidates use the component's delivery representation. For signed components this
is a complete `SignedData` envelope, even though their working cursor is unsigned.
Checks inspect the candidate without installing it, publishing, signing or stamping.
`AKeyedRegions` delegates present known entries to its existing registered children;
unknown regions are outside its policy, and inspection does not freeze registrations.
The favourites domain view checks the envelope's payload using its existing
`Timestamped` and `SetComponent<ACell>` children, without a separate schema tree.

`SetComponent(cursor, CVMLong.class)` supplies an explicit runtime element type;
this is an optional diagnostic constraint, not a default schema. Java generics
alone cannot check foreign elements. Domain subclasses may override
`checkElement` for further constraints. The one-argument constructor checks only
set shape. Nil represents an absent set; it is compatible without creating storage.

No generic read, edit, transaction, merge or sync automatically calls `checkValue`.
Local domain operations preserve their invariants by construction. Incoming merge
remains responsible for acceptance and may reuse compatibility checks where useful,
usually on changed incoming parts. Existing merge-time validation remains in place;
these diagnostics do not automatically strengthen an application's payload policy.
Direct raw cursor writes remain the caller's responsibility.

**Relay acceptance does not imply domain understanding.** An owner region may
verify signatures and authorisation, and inspect only the metadata its merge needs.
The favourites region, for example, orders signed records by timestamp without
checking their items. It retains and republishes the complete winning envelope,
including unfamiliar fields and representations. A local domain view may report
incompatibility without preventing the region from relaying that record.

Read or edit only what an operation needs. The examples expose remote task titles
as cells and use `SetComponent<ACell>` to preserve unfamiliar set members. Adding a
numeric favourite or a text note does not require every existing member to have
the same type. Editing one field preserves all unrelated fields. Set operations
on a non-set field throw `IllegalStateException` without changing it; they never
silently treat an unfamiliar representation as empty. Nil still means absence.
A caller can inspect the original value through the region or its raw record.
These are operation requirements, not recursive schema validation. Numeric score
merging, by contrast, requires numbers because it orders values numerically.

**Components must be robust to external change.** Whatever it chose above, a
component must allow for the value at its position changing under it at any time:
another component publishing into the same region, an incoming merge, or a fork
syncing. A live component achieves this with read-modify-write atomic updates; a
staged one by merging on sync. Neither overwrites. Components sharing a region must
agree on its lattice; two different rules at one path do not converge. That is why
region definitions are catalogued once and reused.

**An application is the component at the host root.** It may extend `AKeyedRegions`
to compose named child components, or `ALatticeApplication` to manage a custom
hierarchy and incoming merge. Both use the same application lifecycle. Either may
be configured on the host to receive incoming
values and decides when its work is published. A regions application routes values
to registered children; each child supplies its own merge and publication boundary.
The lattice-based application may be live in the host root or staged over it.
A host may hold several applications. Conceptually they
are separate apps that synchronise with each other through the root: each publishes
into it and observes the others' published state there, immediately when live and on
sync when staged. They may share regions provided they share each region's lattice.
A keyed root merges the regions it knows and ignores the rest, so nodes with
different region sets still interoperate.

**The host is the authoritative local root.** It holds the published root value, an
optional store, the publication policy that runs on its sync, and the durability
barrier. It knows no lattice and no path structure: every incoming value is handed
to the applications configured to receive it, and without applications incoming
values are ignored. A node adds replication around the same host. It receives values
from peers, hands them to the host, and sends the host's published root to its
propagation sets. The node replicates what it is given; update and merge semantics
belong to the host's applications and components. Application code is identical
against a local host and a networked one.

### How state moves

- **A local write** goes domain operation, component, cursor atomic update. Any
  stamping or signing happens inside that update, at the boundary the component
  wrapped.
- **Publication is explicit and layered.** A component's sync publishes one snapshot
  into its destination and stops there. An application chooses which components to
  sync and in what order. The host's sync persists the root and notifies
  replication. Flush is the physical durability barrier. Persist, sync and flush are
  three different things, and nothing is published, persisted or sent unless the
  owner of that state asks.
- **An incoming value** arrives at the node, reaches the host, and is offered to
  each application configured to receive it. The application verifies envelopes,
  merges under its lattice and discards what it does not accept. A live application
  merges straight into the host's root, so live views see the result at once. A
  staged application merges into its working copy and publishes when its policy
  says. Receipt never implies republication.
- **Batching** is a fork: edit privately, publish once, and let conflicts resolve by
  merge rather than by failure. A transaction is the stricter form for one atomic
  multi-step change, committed by compare-and-set and retried.
- **Deletion** under a union merge needs a tombstone, or a whole-value register
  with a timestamp, because an older replica would otherwise resurrect the key.

**Decisions still open.** This document states the intended design; the
[design log](LATTICE_DESIGN_LOG.md) records how it got here. Still to decide:

- Lifecycle and subscriptions: principles agreed, API not implemented. See
  *Lifecycle and subscriptions*.
- Vocabulary: the component base-class name, and whether "application" names only
  the component at the host root (design log entries 12 and 17).
- Typed owner slots, so a forgotten signing boundary fails at compile time rather
  than at run time (entry 11).

The CADs distinguish abstract rules from application implementation:

| CAD | Scope |
|---|---|
| [CAD024](https://docs.convex.world/docs/cad/data_lattice) | Abstract value, validation and merge semantics |
| [CAD038](https://docs.convex.world/docs/cad/lattice_auth) | Signed ownership and authorisation rules |
| [CAD045](https://docs.convex.world/docs/cad/lattice_apps) | Components as the normal application implementation mechanism |
| [CAD035](https://docs.convex.world/docs/cad/cursors) | Atomic storage, projections and fork reconciliation used by components |
| [CAD036](https://docs.convex.world/docs/cad/lattice_node) | Delivery and replication of hosted state |
| [CAD053](https://docs.convex.world/docs/cad/lattice_regions) | Shared region names and references to their semantic definitions |

Abstract lattice definitions remain useful specifications and reusable merge
implementations. Components bind those rules to storage, policy and publication;
their object graph need not mirror a lattice expression. The corresponding CAD
source updates are in the sibling `design` checkout. Deployment of those pages
is separate from these local changes.

## Architecture

```
Domain components        DLFSDrive, Social        one component per path
Application component    AKeyedRegions            named children, delivery and publication
Application base         ALatticeApplication      lifecycle and managed child publication
Optional adapter         LatticeApplication       supplied lattice, navigation and root merge
Lattice host             LatticeHost              published root, optional store, publication policy
Cursor                   path, CAS, drafts        atomic state access
Optional host runtime    NodeServer               replication only
```

`LatticeHost` is the developer-facing host. It holds the published root and the
publication policy, and optionally a store whose lifecycle stays with the caller. It
knows no lattice. A node can host the same root and installs its replication
pipeline during launch; application code never knows a node exists.

## Composing named regions

The default component publication boundary is explicit in its constructor:
`super(cursor)` shares working storage and makes `sync()` a read-only no-op;
`super(forkOf(destination))` owns a private working copy that sync reconciles into
that destination. A live view never publishes a caller-owned fork, including when
the view is a path into that fork. The component-copy constructor `super(source)`
creates a fork and retains the source's context. Application lifecycle methods
explicitly add preparation and child publication; ordinary components inherit
neither operation.

Extend `AKeyedRegions` for an application whose interests are named regions of an
index. Register a child for each region. The same class works at the host root or
inside another regions component; it has no whole-container working copy to merge
or publish.

The concrete implementations cover ordinary assembly: `SingleRegion<C>` wraps one
typed child and exposes it through `component()`; `Regions` collects several
children. Both work at the host root or a nested index and inherit the same merge
and publication behaviour. A single-region application can be as small as:

```java
var host = LatticeHost.create();
var app = host.addApplication(new FavouritesApp(host, getTimestamp));
app.user(alice).add(42);
app.sync();
```

`FavouritesApp` extends `SingleRegion<KeyOwnedIndex<…>>`: it constructs the standard
owner index at `:favourites` and exposes domain operations such as `user(key)` and
`items(owner)`. No custom region class is needed. Its alternative constructor
accepts an existing owner index to share pending state.

The host registers root applications, with no knowledge of their region names or
paths. `addApplication(app)` returns the same instance, retaining its policy and
publication boundary. Applications receive deliveries in registration order.
Register before the first `host.merge` or `host.mergeAt`, which freezes the list,
including an empty list. `configureApplications(list)` remains available for one
bulk setup; further applications may be added before delivery starts.

For simple assembly without a domain API, `new SingleRegion<>(host, key, component)`
and `SingleRegion.create(host, key, factory)` remain available. For several regions, construct
`new Regions(host)` and call `create(key, factory)` or `add(key, shared)` during
assembly. `new Regions(host, components)` also accepts a map of existing children,
registered in its iteration order. The cursor-and-context constructors and factory
support the same composition below the root. Factory methods have separate names
from existing-component registration to avoid ambiguous Java lambda inference.

For applications declaring their own collection, the base class remains useful:

```java
final class Counter extends LatticeComponent<AInteger> {
    Counter(AForkableCursor<AInteger> destination, LatticeContext context) {
        super(MaxLattice.INSTANCE, destination, context);
    }
}

final class Counters extends AKeyedRegions {
    static final Keyword COUNT = Keyword.intern("count");
    final Counter count;

    Counters(LatticeHost<Index<Keyword, ACell>> host) {
        super(host);
        count = createRegion(COUNT, Counter::new);
    }

    Counters(LatticeHost<Index<Keyword, ACell>> host, Counter shared) {
        super(host);
        count = region(COUNT, shared);
    }
}

var host = LatticeHost.create();
var first = new Counters(host);
var second = new Counters(host, first.count);
host.configureApplications(List.of(first, second));

host.merge(Index.of(Counters.COUNT, CVMLong.ONE));
// Both applications see the same pending value through their shared count.
second.sync();                  // publishes the child at [:count]
host.sync();                    // optional persistence / replication
```

`createRegion(key, factory)` supplies a cursor at the key and the current context
before constructing the child. It creates an absent containing index on the first
changed write, not on construction or read. A nested regions factory receives an
`AForkableCursor<Index<Keyword, ACell>>` and constructs another `AKeyedRegions` over
it. Java constructor references to overloaded constructors may need explicit factory
type arguments.

`region(key, component)` registers an existing instance. It preserves that
component's context, destination and pending state. The caller is responsible for
binding it to the intended region: registration does not relocate it. Applications
sharing one instance share pending changes, and either can publish them. Two separate
staged components at the same destination instead have independent working copies,
reconciled on publication. Duplicate delivery to a shared component is possible;
standard lattice merges are idempotent, and custom handlers must support this use.

The composition has the following contracts:

- A root merge delivers only present, registered entries. An empty regions
  application ignores all incoming roots and paths. An absent key does nothing;
  an explicitly present null is delivered to its child.
- Root and path deliveries both invoke the child's `mergeAt` entry point. The
  child's custom merge remains authoritative. Signed children require a complete,
  authorised envelope and reject unsigned values at their slot; paths inside an
  envelope deliver nothing.
- `sync()` calls each child in registration order and never writes the enclosing
  index. Unknown siblings, including concurrent updates or removals, are preserved.
  Neither merge nor publication is atomic across children; earlier successful work
  remains if a later child fails.
- The container cursor shows the destination. Read staged edits through the child
  component's cursor. A live child changes the destination immediately; use
  `LatticeComponent.live` when that is the intended boundary.
- Duplicate registrations are rejected. The first merge, path delivery or sync
  freezes the registrations, without freezing or reconfiguring shared children.
- Host publication and durability remain separate from application sync.

`ALatticeComponent` does not require an `ALattice`, expose `getLattice()`, or infer
structure from a lattice tree. It provides working storage, child routing and
publication. A component implements `merge(incoming)` for incoming values and
`mergePublication(own, other)` to reconcile staged edits with a changed destination.
The latter must operate on the supplied snapshots without changing live storage.
An unresolved descendant delivery goes to `mergeAtPath`; the base rejects it.
Containers such as `AKeyedRegions` route through their registered children.

`LatticeComponent` is the optional adapter for simple values and caller-supplied
`ALattice` implementations. It supplies lattice-backed merging, path reduction and
publication, including preservation of unrelated destination keys for keyed lattices.
Domain components normally extend `ALatticeComponent` and call reusable lattice
rules in their own operations. Using a lattice internally does not imply adopting
the adapter's incoming merge or path routing.
`getLattice()` and lattice-directed `resolve` belong to this adapter.
A component that needs an atomic aggregate should own that aggregate's
publication boundary and supply its complete merge rule. Declaring a partial
lattice does not move a component's cursor to a narrower boundary.

`ALatticeApplication` extends `ALatticeComponent` and adds lifecycle, with no
required lattice definition. Its `sync()` runs `beforeSync()`, `syncChildren()`,
then publication of its own working boundary. Failure stops subsequent stages;
completed child publications are not rolled back. Hooks must support concurrent
calls. `AKeyedRegions` extends this base and implements child publication in
registration order. Registrations freeze before preparation begins. A nested
regions application runs its own lifecycle when its parent syncs that child.

`LatticeApplication` is the optional application adapter for an explicitly supplied
`ALattice`, including generic merge, publication reconciliation and path navigation.
P2P and DLFS applications with caller-supplied root lattices use this adapter.
Ordinary domain applications need neither `getLattice()` nor a parallel lattice tree.

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

**Choose merge rules bottom-up.**

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
children with different rules in an index, or `StringKeyedLattice` for a
string-keyed hash map. Both provide per-child merge, navigation and the right empty
value. Write a custom lattice only for semantics the standard types cannot express;
it must define merge, child lookup and empty value consistently.

**Bind the child to its key when the rule depends on it.** The map, index and owner
lattices extend `AUnionLattice`: a union of keys where each entry merges with the
child `path` returns for its key, and a key with no child is dropped. A collection
whose entries are valid only under a particular key, an owner's slot or a
content-addressed entry such as a mail message under `issued-at ‖ hash`, extends the
same base and returns a key-bound child from `path`, so navigation and merge cannot
disagree about the rule at a key. Not every map merges this way: a map that is the
leaf of a last-writer-wins rule keeps whole-value replacement, with navigation below
it for writes only.

**Wrap with an owner lattice** for self-sovereign data:

```java
OwnerLattice<Index<Keyword, ACell>> SOCIAL_LATTICE = OwnerLattice.create(SocialLattice.INSTANCE);
```

**Register with the root.** A node opts in by adding the lattice under a keyword,
which becomes the first path element. Existing regions are catalogued in
[LATTICE_REGIONS.md](LATTICE_REGIONS.md); reuse a catalogued definition rather than
redeclaring it, so every node sharing the region merges it the same way.

```java
KeyedLattice root = Lattice.ROOT.addLattice(Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);
```

## Building the application layer

**Compose from a generic host.** Application factories accept the host:

```java
public final class MyApplication extends LatticeApplication<Index<Keyword, ACell>> {
    private final Social social;
    private final DLFSRegion files;

    private MyApplication(LatticeHost<Index<Keyword, ACell>> host) {
        super(LATTICE, host);
        social = Social.connect(this);
        files = DLFSRegion.connect(resolve(Keyword.intern("documents")), host.store());
    }
    // This application's rule for an incoming root: the lattice's default merge
    @Override public Index<Keyword, ACell> merge(Index<Keyword, ACell> incoming) {
        return cursor().updateAndGet(own -> getLattice().merge(getContext(), own, incoming));
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
neither. A node serves peers from the host's store, so published roots never refer
to data the store lacks. Publication policy is a lifecycle concern: a host defaults
to publishing into its store, and a node installs and freezes its own policy during
launch.

**One component, one path.** Each level of the data model is a component holding a
cursor at one path and creating the components one level deeper:

```
Social        cursor at the owner lattice level
  SocialUser  cursor at the per-user record (through the signing boundary)
    Feed      cursor at Index<Blob, ACell>
    Follows   cursor at AHashMap<ACell, ACell>
```

Hosts, applications and domain components are all components. A live component
holds a working cursor; a staged component also holds a destination. Only the host
owns persistence policy. DLFS components additionally carry a store handed down
explicitly from their application, because streamed file writes checkpoint blobs
before publication. The cursor is the only connection back to application state:
there is no second parent hierarchy, and cursors know nothing of storage. The
component that owns an owner-keyed or stamped level wraps that boundary itself, so
the boundary sits exactly where the data model places it and descendants never see
it. The social component wraps the owner's slot; the feed component never sees a
signed envelope.

Long-lived application and region components are fine. Owner and session views
should be held by the service that routes them, or recreated on demand, never
accumulated in an unbounded cache.

**Write through cursors.** Use read-modify-write updates. The update function
receives the current value, and the component supplies its own initial value in the
same atomic update when the path may be absent:

```java
public void putPost(Blob key, String text) {
    long ts = getContext().currentTimestampValue();
    AHashMap<Keyword, ACell> post = SocialPost.createPost(text, ts);
    cursor().updateAndGet(feed -> (feed == null ? SocialLattice.FEED_LATTICE.zero() : feed)
        .assoc(key, post));
}
```

This example writes an explicitly chosen key. The feed's post operation allocates a
free key starting at the operation timestamp and advances the key on collisions
without changing the post's timestamp; key allocation is separate from the write
clock.

Reads return null for absent paths. Components with a known container type can use
`cursor.withDefault(Maps.empty()).path(key)` or the corresponding index default:
reads and navigation create no state, and the first changed write constructs the
container atomically. This does not require a lattice definition. The optional
`LatticeComponent.resolve` helper uses lattice zero values when the implementation
already has a lattice describing its shape. A plain path write requires the
intermediate containers to exist.

**Keep record construction in static helpers** (`SocialPost.createPost`), separating
data format from cursor mechanics. Rules of thumb:

- Always include `:timestamp` in LWW-merged records; it is the tiebreaker.
- Use `Keyword` keys for record fields and `Blob` keys (big-endian timestamps) for
  ordered collections in an `Index`.
- Use `Index` for lattice-level containers, where child lookup and empty value
  matter, and `AHashMap` for leaf records and dynamic key sets. `Index` resolves
  `Keyword` and `AString` keys identically, which matters for JSON interoperability.

## Application root and host

An application defines which incoming values it accepts and how its components
merge them. `AKeyedRegions` normally supplies root selection and routing; each
registered child owns its policy and publication boundary. Custom hierarchies can
extend `ALatticeApplication` directly. Domain components receive cursors at their
own paths and need not navigate from the root.

For callers that already have a root `ALattice`, `LatticeApplication` supplies the
standard adapter. Its lattice describes container types and value merges; it may
serve a relay without constructing domain children. The following example opts
into that adapter:

```java
var host = LatticeHost.create();
var app = new LatticeApplication<>(selectedRegions, host);
host.configureApplications(List.of(app));
host.merge(incomingRoot); // delegated to the application's working copy
app.sync();              // merge the working copy back to the host
host.sync();             // optional host policy; otherwise a no-op without a store
```

The runnable [favourites example](../src/test/java/convex/lattice/app/FavouritesExample.java)
is one application over an in-memory host. Each user's edits are signed immediately
through a signing cursor into its favourites region's working copy. The application
extends `SingleRegion` over a standard `KeyOwnedIndex`, so its sync publishes that
child at `[:favourites]` and leaves unrelated host regions alone. Two applications
may share the same region instance and its pending edits. Each application takes a
`Supplier<CVMLong>` for local authoring; the tests supply the mutable clock. A
[persistence variant](../src/test/java/convex/lattice/app/FavouritesPersistenceTest.java)
publishes to Etch and reopens the same application.

`Favourites` embeds a `SetComponent<ACell>` for its `:items` field. Its convenience
methods add and remove numeric IDs, while retaining unfamiliar members supplied
by other versions. The application's field accessor exposes the relayed value directly.
The standard
component supplies atomic `add`, `remove` and `clear` operations, membership and
size queries, and immutable snapshots through `get()`. It treats an absent set as
empty without creating state. Its cursor passes through the favourites record's
`Timestamped` component and signing boundary, so a changed set and its timestamp are
signed together. No-op edits retain the existing timestamp and signature.

Components access their editing view through `cursor()`, including inherited
operations. A wrapper is constructed once from `super.cursor()` and returned by
the override; there is no protected backing field for domain methods to bypass it.

The record cursor explicitly uses `withDefault(Maps.empty())`, which presents an
empty record without storing it. The composition is:

```java
record = Timestamped.create(super.cursor().withDefault(Maps.empty()), getTimestamp);
itemSet = new SetComponent<>(cursor().path(ITEMS));
```

`Timestamped.cursor()` is the local editing cursor, implemented by `StampedCursor`.
The component's `merge` uses its underlying storage, so incoming records keep their
timestamps. `Timestamped.lattice()` supplies the same whole-record ordering to the
enclosing owner index; the application defines neither a timestamp extractor nor
a stamping lambda. Higher timestamps win and ties retain the local record. The
standard factory requires hash maps with a `CVMLong` `:timestamp`; custom record
representations can supply an ordering lattice and stamping function explicitly.
Incoming partial paths do not update a timestamped record.

The wrapper creates no working copy or publication boundary. Its `sync()` does
not publish the enclosing region. A staged enclosing component uses the same
lattice for publication conflicts, without re-stamping the winner. Favourites
also exposes this editing cursor through its own `cursor()`, so direct local
edits receive the same stamping as embedded set operations. Incoming signed
envelopes continue through the signed boundary, retaining the winning signature.

These defaults belong at their respective
levels: the record chooses `{}`, while the set component handles `nil` as `#{}`.
No projection functions are needed in the application. `withDefault` also supplies
the default to atomic update functions; a changed write creates storage, and a
null write clears storage so subsequent reads see the default again.

The set component supplies local collection operations; the containing component
owns replication semantics. Favourites still merges complete signed records by
last-write-wins, so removing an item or clearing the set survives replay of an
older record. Embedding a set does not introduce a union merge or an independent
publication boundary.

A host's store is optional. Without one the host is purely in memory: persist
returns its input, flush does nothing, and sync returns the current snapshot unless a
publication policy is configured. With a store, sync selects the published root in
that store and flush is its durability barrier; persist writes reachable cells
without selecting a root or moving the cursor. The caller owns and closes the store.
A node requires a host with a store, because it serves cells to peers from it.

Opening a host restores the retained root, or starts at the supplied initial value,
by default an empty keyed index, if the store has none. Creating a host deliberately
starts a fresh root; its first publication replaces any retained root. Applications
accept a host rather than a store, so this choice belongs to host assembly. Each
application supplies its own component behaviour. An application may override `merge` to hand
subtrees to components with their own merge rules and discard the rest; verification
must still use the owner-bound rules for the paths it publishes. Merging keeps the
current local value in the `own` position and does not publish or flush.

### Receiving incoming values

Configure once, before any incoming value can arrive, which applications receive
them. For a shared root with one rule for what is accepted, one live application is
enough:

```java
var incoming = LatticeApplication.live(rootLattice, host);
host.configureApplications(List.of(incoming));
host.merge(incomingRoot); // verifies and merges into the shared root once
```

Domain components then use live paths in this root or fork their own. Nothing
requires every application to receive incoming values; observing accepted root
changes and choosing when to publish private work are application policy. The
Social + P2P + DLFS example instead registers its domain components directly, as
shown below.

Several applications may be configured when they accept differently. A staged
application merges incoming values into its working copy. An application can route a
signed subtree to a signed component, which verifies the envelope before merging its
payload into the unsigned working copy. Discarded input stays discarded. Enclosing
signed envelopes must be verified before interior values are extracted and routed.

Delivery is not a transaction across applications: if a later application fails,
earlier merges remain, and lattice delivery is safe to retry. An explicitly empty
application list and an unconfigured host both ignore all incoming state. A node
routes incoming pushes, whole-root and path pulls, direct merge calls and snapshot
candidates through the host; delivery never invokes application sync, and an
acknowledgement confirms delivery, not publication of private work. A path-addressed
delivery goes to each application's `mergeAt`. Registered children handle the
remaining path; otherwise the component's `mergeAtPath` determines what is accepted.
The base rejects unsupported descendant delivery, while keyed regions ignore
unregistered keys. Only the optional lattice adapters reduce a descendant delivery
to a partial aggregate using declared empty containers, then call their own `merge`.
Owner components stop at signed boundaries: callers supply the complete envelope.


A node is replication. It hosts an assembled `LatticeHost`, attaches propagation
groups, hands incoming values to the host and sends the host's published root to
its propagation sets. It holds no lattice and performs no merge of its own. Assemble
the host and its applications first, then start the node:

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

The supplied host is already initialised, restored by `open`; the node never reloads
it. Launch installs the node's publication pipeline, so the host's publication
policy must not already be frozen. Node startup does not invoke application sync.
Transport and propagation configuration remain explicit, and a propagation group
knows no lattice either: it announces whatever the host publishes, through its own
projection. A relay node is the same assembly with `LatticeApplication.live(lattice,
host)` as the host's one application; the peer module's `RelayHost` assembles that
host from a lattice, a store, node configuration and the relay's policy.

### Sharing a host

A host may hold several applications. They are separate apps that synchronise with
each other through the root: each publishes into it and observes the others'
published state there. Several applications and components may therefore publish
into the same host, and into the same regions. Each chooses its boundary: a live view
shares its path in the host and has nothing to publish; a staged component publishes
its subtree on sync. Either way the component allows for the destination having
changed under it, so sync reconciles before it installs:

```java
// Infrastructure is shared live and registered once.
host.addApplication(LatticeApplication.live(P2PLattice.ROOT, host));

final class SocialApp extends AKeyedRegions {
    final Social social;
    SocialApp(LatticeHost<Index<Keyword, ACell>> host, LatticeContext policy) {
        super(host);
        setContext(policy);
        social = region(Social.KEY_SOCIAL, Social.connect(this).fork());
    }
}

var socialApp = host.addApplication(new SocialApp(host, socialPolicy));
var files = DLFSRegion.connect(host.cursor().path(Keywords.FS), store,
    fileKeyPair::signData, Utils::getCurrentTimestampCell);
var filesApp = host.addApplication(new DLFSApplication(host, Keywords.FS, files.fork()));

socialApp.social.user(ownerKey).feed().post("A staged post");
P2PUser.create(socialApp, ownerKey).node().setNodeInfo(nodeInfo); // live P2P state
socialApp.sync(); // publishes :social, preserving :fs and :p2p
filesApp.sync();  // publishes the exact region returned by filesApp.dlfs()
host.sync();     // persistence and propagation are separate
```

Social and Files are two applications on one host. Each groups domain components
with their own boundaries and chooses when to publish. They need no common private
root or combined lattice. P2P views obtained independently by both see the same
state. This is the shape of the [shared applications example](../../convex-integration/src/test/java/convex/lattice/SharedApplicationsExample.java).

A staged component knows only its working copy, its destination cursor and its own
lattice and policy. The destination can be a path inside any application tree.
Publication updates that cursor and stops; whoever provided the cursor controls what
happens above it. Components construct missing containers; raw path cursors do not.

Publication captures one snapshot of the working copy. If the destination is
unchanged since the last publication, the snapshot is installed directly; if the
working copy is unchanged, it adopts the destination. Only when both changed are they
merged, with local edits as `own`, so equal-timestamp local edits keep that
precedence. Working writes are ordered before or after publication using the fork's
transactional working root. All reconciliation and preparation precede the
destination CAS; failure commits no working value, baseline or destination change.
A live view
needs no sync to observe root changes; a staged component sees them when its policy
calls sync, which reconciles and publishes in one step.

Reconciliation at a signed slot follows the signed lattice's rule. When the result
equals either input's payload, that envelope is reused and no signer is needed. A
new payload requires an authorised signer, so a component that cannot sign for the
owner keeps the rule's selection. In the shared example, incoming feeds and files
merge directly into their staged region components. These combine remote values
with pending local edits and sign synthesised owner values. The host does not see
those regions until application sync. Divergent edits for one owner belong in a
component that can sign for that owner.

Syncs of separate paths are not atomic together. For a consistent read of several
published paths, capture the immutable host root once and read each path from that
value. An operation that must change several paths atomically uses a transaction at
their common ancestor.

Staging is a per-component choice at any level, and the root is not a special case:
an application staged over the host is a staged component whose position is the root.
Its sync merges its copy with the destination under its lattice, with local edits as
`own`. A staged component with a keyed lattice publishes only the keys its lattice
declares; every other key adopts the destination, so a component that understands
some of a container's keys cannot overwrite or remove what another component owns
there. A declared key it did not edit follows the lattice rule rather than adopting,
which differs only on an exact tie. What a component's own staged children publish is
theirs: each publishes through its own fork of the host, beside the parent's copy,
and the parent's merge takes those regions from the host. Nesting a staged component
inside another component's working copy is the shape to avoid.

The whole root, a domain region and an owner's slot are the three usual boundaries,
chosen by what the component edits: a relay lives at the whole root, a domain
grouping forks its region, and an owner's batched edits want a signed component,
because only there can a new payload be signed.

These boundaries are not an access-control boundary between code sharing a process.
Verification is bound to the lattice instances, configured before use.

### Batched signing

For immediate signed edits, a live component wraps its destination with
`SignedCursor.create(slot, key)`. It needs no working copy and no sync of its own.

Deferred signing is an explicit choice. A signed component (`SignedComponent`) owns
an unsigned working copy and a signed destination. Bind it to the owner-bound signed
lattice at that host path, so it carries the owner verifier. Domain components use
its working cursor through their rebinding factories and update normally, without
signing each edit:

```java
var signed = new SignedComponent<>(owners.signingFor(owner), signedSlot, ownerKey::signData);
var user = existingUser.withCursor(signed.cursor());
user.feed().post("First post");
user.feed().post("Second post");
signed.sync(); // one signed publication to signedSlot
```

On sync, the component merges the unsigned payload with the destination, signs the
result once and publishes the envelope. Unchanged payloads do not sign. A signing
failure leaves the destination unchanged. Sync is not a rollback transaction: a
completed publication stands even if later reconciliation fails. Untrusted envelopes
enter through verified merge; publication reconciles only values the application has
already accepted.

Applications choose their components and publication order explicitly:

```java
public void sync() {
    socialOwner.sync();
    nodeOwner.sync();
}
```

There is no component registry, parent link, scheduler or hidden worker, and no
atomic transaction across components: a completed publication stands if a later one
fails. Each signed component publishes through its own fork of the host path it owns.
An application prepares in `beforeSync()` and publishes managed components in
`syncChildren()`, before publishing any staged aggregate of its own. Keyed regions
already implement this child stage; subclasses normally customise preparation only.

## Component transactions

A transactional component (`TransactionalComponent`) adds typed transactions,
synchronous, asynchronous and locked. Social, feed, follow, identity, node-record and
DLFS components implement it, and any component subclass can opt in.

```java
user.transact(u -> {
    u.feed().post("Hello");
    u.follows().follow(otherDid);
});

user.transactAsync(u -> u.feed().post("Another post"))
    .thenRun(this::notifyLocalCommit);
```

The callback receives a private view of the component. Its child components share
the same private state, and the final value is installed atomically through the live
cursor's signing and stamping boundaries. An exception discards the staged state.
Callbacks may run more than once, so they must be pure, confine their changes to the
supplied view and leave external effects until after completion. The draft uses an
independent, thread-safe root. Complete any worker tasks contributing edits before
returning from the callback. Retained views and forks still work afterwards, but
their edits and fork syncs only update private draft storage, never the live state.
The transaction protects only its subtree: decisions must not
depend on live components outside it. Put such operations at their common ancestor.
Completion means committed locally, not persisted, published or acknowledged by
peers.

The host selects its root concurrency policy at construction. Forks always use
independent transactional working storage, whatever the host policy:

| Root | `transact` | `transactLocked` | Ordinary writes |
|---|---|---|---|
| Ordinary root (default) | Optimistic retries | Throws before invoking the callback | Original atomic operations |
| Transactional root | Optimistic, with serial fallback under contention | Callback runs once under root exclusion | Commits coordinate through a root lock |

```java
// When creating a node:
NodeConfig config = NodeConfig.create().withTransactionalRoot(true);
```

The lock belongs to the shared root and covers every view and write on it, including
unrelated regions. Ordinary root reads remain lock-free; fork reads take its working
lock so they cannot observe an incomplete sync. Locked callbacks must stay short
and must not wait for another operation on the same state or publish state.

For a custom component, implement the interface with a rebinding factory that
creates a fresh component over a supplied cursor with the same domain metadata and
resolved context. Rebuild children against that cursor rather than reusing cached
live children. A component owning configuration, such as a DLFS store, passes it
along itself.

## Fork and sync

A component fork is an independent working copy. Its sync merges the copy into the
destination it was forked from, with lattice semantics: concurrent state is merged
rather than treated as a conflict, though verification, signing or storage can still
fail. This is separate from publication and durability:

```java
Social forked = social.fork();  // social is live in the host root
forked.user(myKey).feed().post("Post 1");
forked.user(myKey).feed().post("Post 2");
forked.sync();   // merge the fork's region into the host root
host.sync();     // persist and propagate the hosted root
host.flush();    // optional physical durability barrier, a host operation
```

Domain components expose a fork with their exact type when batching is a use case;
sync is inherited from the component base.

| Operation | Meaning | Moves a cursor? |
|---|---|---|
| Host persist | Write the current root's reachable cells to the host store; return the store-backed value | No |
| Component sync | Publish into its destination; a live view has no publication work | Yes, for a staged component |
| Host sync | Run host persistence and replication policy on one snapshot | May install persisted references; concurrent edits remain pending |
| Host flush | The store's physical durability barrier | No |

Incremental writers with their own store, such as DLFS drives, checkpoint at
intervals to swap direct references for store-backed soft references and relieve
memory pressure. Persistence neither selects a retained root nor licenses garbage
collection.

### Persistence

Persistence lives in two places and nowhere else:

- The host owns the host store, when it has one. It publishes the root on sync, can
  persist reachable cells without publishing, and provides flush, the physical
  durability barrier. Staged applications publish into the host before its own sync
  persists or propagates their state.
- DLFS components carry a store explicitly, because streamed file writes checkpoint
  blob branches before anything is published. The DLFS application takes the host
  store at construction and hands it to regions, drive maps and drives; forks and
  transaction views keep the same store. A drive without a store keeps file data on
  the heap until the root is published.

Cursors have no persistence API. A transaction rollback or an abandoned fork may
leave immutable cells in the store; they are unreferenced and reclaimable, never a
logical state change. Forking below an owner's signing boundary still defers that
signature until merge-back, so a DLFS fork can make many edits, checkpointing blobs
as it goes, and sign once on sync.

## Cursor boundary

Cursors provide atomic access, navigation, transaction drafts and forks. They carry
no context, perform no lattice merges and construct no containers. A fork takes its
merge function when it is created and reconciles with its destination on sync; a
staged component owns such a fork and supplies its lattice as the function. A plain
path write requires its intermediate containers to exist, and
update callbacks receive `null` for absent values. The component owning a level
supplies what the cursor lacks: its lattice's empty value when creating a child
position, and bound signing or stamping functions when wrapping a boundary. Cursor
mechanics are in [LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md).

## Explicit write capabilities

Signed components take a `Function<V, SignedData<V>>`. A supplied key needs only a
method reference; a wallet can supply a function which resolves its current key
when called. The component uses this same function for local authoring and any
re-signing. No signing context needs to be constructed or inherited.

```java
// In a signed domain component's constructor:
super(destination, ownerRule, myKeyPair::signData);
```

Owner verification belongs to the bound lattice and cannot be replaced by the
signer. The rule checks the requested payload, signature and owner authorisation
before storage. A null result means signing is unavailable: local writes and draft
publication fail without changing the destination; merge synthesis keeps the
existing envelope. Returning a different payload or an invalid signature throws.
Selecting an existing envelope and unchanged writes never call the signer.

`SignedLattice.merge(own, other)` never signs. Explicit authoring uses
`mergeAndSign(own, other, signer)` or `sign(value, signer)`. `LatticeContext` remains
available to existing components and legacy lattice APIs, but setting it cannot
change the signer supplied to a signed component. Cursors carry no context.

**Time.** The timestamp is resolved once per logical write, even under retry, and
used exactly: no incrementing, no comparing with stored values, no hidden logical
clock. Reusing a timestamp deliberately creates a tie, which the local value wins.
Where ordering across replicas matters, especially delete followed by recreate, the
application supplies timestamps that express it.

**Signing.** Signing as an owner is the single authorship rule, and it is exactly
what owner verification checks on merge. A key owner requires that key; an indirect
owner such as an address or DID is resolved by the owner verifier. A write the policy
cannot author fails, so a slot no peer would accept never reaches local state.

## Security model

An owner lattice maps owner keys to signed envelopes and, on merge, rejects any value
whose signer does not match the owner. The same rule runs when a value is written
locally. A merge that would have to synthesise a value for an owner this node cannot
sign for keeps its own value instead; merging validly signed data never fails and
never attaches a non-owner signature.

Local state is trusted in the sense that matters: nothing polices what an owner
writes into their own slot, only that slots are written in a form peers accept.
Write adversarial tests that construct forged state at the raw data level and assert
that the owner lattice's merge drops it.

## Owner identity policies

Use an owned-map component for a region containing `owner -> SignedData<V>`:

```text
ALatticeComponent<M>                (abstract component base)
  AOwnedMap<K,V,M>                    (abstract common ownership behaviour)
    OwnedMap<K,V>                    (AHashMap storage)
    OwnedIndex<K,V>                  (Index storage)
      KeyOwnedIndex<V>               (owner public key must equal signer)
```

Owned collections take no context in their constructors. Incoming merges and
publication conflicts both use the owner lattice's context-free merge. They can
adopt valid signed inputs but cannot author a synthesised payload; in that case
they retain the existing envelope. An authorised signed domain component can
reconcile and sign the new value, which the collection can then accept. Setting
an inherited component context on the collection does not change this behaviour.

`OwnedMap` and `OwnedIndex` require an explicit authorisation predicate. A component
declares the value lattice and supplies its resolver when constructing the region:

```java
final class Scores extends OwnedIndex<AString, AInteger> {
    Scores(AForkableCursor<Index<AString, SignedData<AInteger>>> destination,
           Authority authority) {
        super(destination, MaxLattice.INSTANCE, authority::isAuthorised);
    }

    Score score(AString owner, AKeyPair signer) {
        return new Score(path(owner), signingFor(owner), signer);
    }
}
```

The domain class extends `ASignedComponent` and takes its signed storage path
in an ordinary constructor:

```java
final class Score extends ASignedComponent<AInteger> {
    Score(ACursor<SignedData<AInteger>> destination, SignedLattice<AInteger> rule,
          AKeyPair signer) {
        super(destination, rule, signer::signData);
    }

    void record(long value) {
        cursor().updateAndGet(old -> MaxLattice.INSTANCE.merge(old, CVMLong.create(value)));
    }
}
```

`ASignedComponent` exposes the unsigned cursor to domain operations and handles
validation, signing and incoming envelope merges. The supplied owner-bound rule
is shared with the containing map. Signing uses the supplied function; changing
its available keys cannot replace that rule. Optional `checkPayload(ACell)` overrides
inspect the unsigned candidate; the base handles envelope shape and unwrapping.
These diagnostics run only through explicit `checkValue` calls. There is no view
factory or callback into the map to construct the domain component.

The component that signs defines its key-resolution strategy in one place. In the
favourites example, `Favourites` supplies `user::signData` in its
constructor. `FavouritesApp` binds the owner path and rule through its standard
owner-index component, with no signing context. The host and region cannot supply
an implicit fallback key. The same function handles local edits and any re-signing
it performs.

An owned map's `signingFor(owner)` supplies its bound authorisation rule directly.
Its `path(owner)` returns signed storage, creating a missing map only
on a changed write. An ordinary `cursor.path(owner)` also works when the containing
map already exists. Construction, reads and sync of an untouched component create
no entries. Components on the same path share edits and sign immediately into the
map's working state; their sync does not publish the map. `get(owner)` reads the
signed entry.

For independent unsigned edits, `draft(owner, signer)` returns a
`SignedComponent` which signs into the map's working state on its own sync. The
signer function is required explicitly; it is never inherited from the map. The
map's sync publishes its own working state without implicitly publishing drafts.
The host's sync remains a separate durability or replication boundary.

Whole-map merges and owner-path deliveries use the same owner-bound lattice.
Unsigned entries, invalid signatures, unauthorised signers and payloads rejected
by the value lattice's `checkForeign` are ignored. A malformed containing map may
abort the merge atomically. Payload validation is supplied by the value lattice;
Java generic types alone do not establish a domain schema. Local authoring also
checks that validator before producing an envelope. No path crosses inside a
signed envelope. Accepted envelopes keep their signatures; a synthesised value
requires an authorised signer.

The [DID-keyed scores example](../src/test/java/convex/lattice/app/OwnedScoresApplicationTest.java)
exercises a component-supplied local resolver, multiple authorised keys and a
replica without signing capabilities. The favourites region uses `KeyOwnedIndex`
for the direct-key case.

Components bind verification rules to their lattice instances. Different paths can compose
different rules, including different rules for the same owner identity, and
navigating to a child or deriving a restricted view preserves the applicable
boundaries.

An owner need not be a public key. A region can map DID strings to signed envelopes
and bind the rule that authorises a signing key for each DID to its owner lattice.
The abstract owner lattice (`AOwnerLattice`) holds the shared machinery; concrete
types choose the container and key conventions:

```java
// The predicate is part of this region's ownership policy.
OwnerMapLattice<AString, MyState> identities = OwnerMapLattice.create(
    stateLattice,
    (owner, signingKey) -> didAuthority.isAuthorised(owner, signingKey));

// Direct public-key ownership with an Index-backed container.
OwnerKeyLattice<MyState> keys = OwnerKeyLattice.create(stateLattice);
```

The predicate answers whether a key may author this owner's subtree, allowing
several authorised keys without reducing every identity to one public key. The
signed lattice still verifies the cryptographic signature separately. The public-key
variant fixes authorisation to key equality and needs no predicate. Container choice
is independent of ownership policy.

The bound predicate is authoritative for this region; an application context cannot
replace it, and nested boundaries each keep their own rules. Components supply
signers and write clocks separately from verification. An application may still
decline to adopt data its lattice would accept. A predicate rejection or an
unresolved identity never falls back to accepting an arbitrary signer. The predicate
grants no signing capability: authoring still needs an explicit signer which can
produce an authorised envelope.

For stateful DID methods, resolve and refresh authority information outside atomic
updates, so that merge never does unbounded network work. Changing the authority
view governs later incoming claims; it does not erase previously accepted data.

The original `OwnerLattice` remains as a compatibility API that consults the
context's owner verifier; new regions should bind their rule to the lattice. DID
method and account authority details follow CAD043 and CAD004.

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
one. The stamped cursor timestamps each changed local write, and a delete is a
read-modify-write that dissociates a key. Missing maps or vectors below the leaf are
constructed by the component's write; navigation does not infer container types.

## Lifecycle and subscriptions (open)

**Not implemented; principles agreed.** Root merge, ownership and publication are
implemented. Application lifecycle and subscriptions are intended but undecided in
detail, and need corresponding CAD updates before being described as normative.

The intended shape: an application has a lifecycle (launch and close) and obtains
typed subscriptions from its lattice tree, each a live cursor at one subtree with
optional change observers. The lattice and domain factory establish a subscription's
value type; unknown paths fail at creation; an absent value at a known path is valid.
Component creation opens no connections, starts no workers and installs no observers.
Launch runs against a locally usable host, not one that has contacted every peer, and
close releases the application's registrations without closing the shared host, store
or peer groups.

A basic installation should need only the application setup, a store and optional
network settings: standard setup assembles the host, the receiving applications and
ordinary propagation, restores the retained root before constructing applications,
configures receivers before accepting traffic and starts transports last. Shutdown
stops incoming traffic, publishes and flushes already-published host state and never
forces private drafts to publish. `RelayHost.open` is the first step in that
direction.

**Design decision:** applications always initiate their own publication. Incoming
delivery invokes application merge and nothing else: no automatic sync, no
preparation hook. An application may publish immediately in its event handling,
batch changes, or wait for a user action. Host publication persists and propagates
only state already published into the host; receiving a value never permits
publishing an application's pending edits.

Signing establishes responsibility for the entire signed subtree. Its owner is
responsible for merging and publishing that tree, including coordinating several
writers using the same signing identity. Non-owner peers never synthesise signed
values, and there is no hidden logical clock.

### Interests as restricted lattice views

A keyed lattice is a typed interest set: the declared keys select the children in
which it participates, and each child defines how that selected data merges. A keyed
lattice expresses a finite set; map and index lattices express a family of keys
sharing child semantics. Compose these for structured records instead of writing a
custom merge for each record.

Runtime subscriptions select paths served by the application's component hierarchy:

- An empty path selects the whole lattice. A non-empty path selects that subtree,
  including future descendants admitted by its lattice.
- Several subscriptions form the union of their selections. An ancestor interest
  covers descendant interests for replication, while each handle keeps its own
  observation path. Overlapping interests across applications are reference-counted.
- An application provides one merge meaning per path, and applications
  sharing a path must agree on it. Subscriptions cannot install competing merge
  functions at the same path; subscription creation does not extend the lattice.
- Interest is demand for acquisition and continued replication, not permission to
  read, write or publish. Peer-group admission and disclosure policies remain
  authoritative.
- Closing the last interest permits replication work to stop. It neither deletes
  retained data nor makes it eligible for collection independently of store policy.

A subscription trie can produce a restricted lattice view internally, without
application writers constructing another lattice or understanding propagator
filters. Subtree paths and unions suffice for the first API; predicates and wildcards
are not required.

**Merge selection and projection are different operations.** A keyed merge ignores
unknown incoming keys but retains unknown keys already present locally. A restricted
serving or observation view must explicitly project selected data; it cannot assume
that merging with a keyed lattice removes everything else. Absence in a partial view
is never a deletion instruction.

Selection respects indivisible merge and authentication boundaries. A subscription
may observe any navigable sub-path, including inside a whole-value LWW register or a
signed tree. Replication acquires and merges the enclosing complete value, then
exposes the requested sub-path. It never truncates a signed value, signs a projection
on the owner's behalf, or treats an LWW field as an independent register. If group
policy prohibits the enclosing value, replication reports that it cannot satisfy the
interest.

### Observation contract

Subscribing establishes interest; watching optionally observes the resulting state,
and closing a watch leaves the subscription active. Each watch receives an initial
snapshot, then changed values at its path, with absence as a null value; the
immutable CVM value is the snapshot. Registration leaves no gap in which a committed
update can be missed. Notifications cover local atomic writes, fork merges into the
live tree and accepted remote merges; an ancestor replacement notifies a descendant
watch only if its value changes. Delivery is asynchronous, serial per watch and in
local installation order; pending states may coalesce to the latest snapshot, so
callbacks observe state and are not a durable event log. A slow or failing callback
blocks neither state installation nor another watch.

### Atomic operations and synchronisation

Components use the cursor's atomic operations. Views at overlapping paths share the
same authoritative state, so concurrent updates retry against the latest enclosing
value instead of replacing it with an earlier snapshot. Update functions must be pure
and may run more than once; IDs and external effects must not be generated inside
them. One logical write resolves the context timestamp once and reuses it across
retries.

Whole-value LWW is an explicit schema choice. A deep update inside such a value must
atomically read and modify the latest enclosing value, stamp the completed write and
cross any signing boundaries before installation, so that two local components
changing different fields preserve both changes. Never implement this as get, modify,
then set. Across replicas or detached forks the selected LWW value still wins as a
whole; local atomicity does not introduce field-wise merge.

| Operation | Completion contract |
|---|---|
| Cursor update | One local state change is atomically visible; publication may still be pending |
| Application merge | The application's own rule for an incoming root, typically the lattice merge under its context; publication remains separate |
| Fork sync | Merge one working snapshot into its immediate parent; concurrent state follows the declared lattice semantics |
| Application sync | Publish the application's components in its chosen order, then its own snapshot if it stages one |
| Host sync | Persist and publish a host root snapshot, then notify configured replication services; no remote acknowledgement |
| Application pull | One pull round for the current interests and routes has merged and published its successful responses; failures complete exceptionally without undoing successful merges |
| Host flush | Complete the host store's physical durability barrier; sync first to include pending working state |

Pull is a local no-op without replication services. Configured replication with no
usable route reports unavailability rather than claiming remote synchronisation.
Pull provides an explicit completion signal, not a claim that all peers are globally
up to date. A root snapshot already published cannot be rolled back by a later
propagation failure.

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
machinery in dedicated regression tests. Follow the repository conventions: no
sleeps, wait on real signals; no fixed ports.

Worked examples:

- The [favourites application](../src/test/java/convex/lattice/app/FavouritesExample.java):
  `SingleRegion` with immediate signing, shared region instances and explicit
  publication, with a persistence variant.
- [`ApplicationPublicationTest`](../src/test/java/convex/lattice/app/ApplicationPublicationTest.java):
  lightweight notes inside a staged, signed notebook registered with `SingleRegion`.
- [`TaskBoardApplicationTest`](../src/test/java/convex/lattice/app/TaskBoardApplicationTest.java):
  one aggregate board region for transactions across task records with stable IDs,
  mutable titles and timestamped completion/reopening, with publication
  as a separate choice. Its `LatticeComponent` base supplies ordinary index merging
  and whole-task delivery routing from `IndexLattice.create(Timestamped.lattice())`;
  the domain code defines task operations and rebinds the clock into transaction views.
- [`SharedRegionsTest`](../src/test/java/convex/lattice/app/SharedRegionsTest.java):
  direct assembly with `Regions`, preserving other applications' published state.
- The [reading-list network example](../../convex-peer/src/test/java/convex/node/app/ReadingListApplicationTest.java):
  applications assembled before node launch, recommendations delivered over a real
  connection.
- [SharedApplicationsExample](../../convex-integration/src/test/java/convex/lattice/SharedApplicationsExample.java),
  runnable from `main`, with its [stories](../../convex-integration/src/test/java/convex/lattice/SharedApplicationsTest.java):
  a social feed and a file store as two applications live at one host root, each
  with its own lattice, each staging its own region, both receiving incoming
  values. A second node pulls the root, pending edits stay private until their
  owner publishes, the applications never disturb each other's regions, and
  everything survives reopening the store.

## Checklist

- [ ] Design the state tree, then choose a merge rule per leaf.
- [ ] Compose lattice primitives bottom-up; use keyed lattices for structured
      records and reserve custom lattices for new merge semantics.
- [ ] Use `OwnedMap`, `OwnedIndex` or `KeyOwnedIndex` for per-owner signed data; register the component under a region keyword.
- [ ] Compose region components with `Regions` or `SingleRegion`; use
      `ALatticeApplication` for custom lifecycle and hierarchies. Choose
      `LatticeApplication` only when adapting an explicit root lattice.
- [ ] Accept a host in factories; provide a local open and a host-neutral
      connect. Assemble host and applications first, then the node.
- [ ] Hand each domain component the cursor for its level; keep region paths in the
      application.
- [ ] Assume the destination can change under any component; reconcile on sync,
      never overwrite. Share a region only with the same lattice.
- [ ] Write with read-modify-write updates, declare child levels with their lattice,
      return exact types from fork.
- [ ] Treat persist, sync and flush as distinct boundaries.
- [ ] Include `:timestamp` in LWW records; tombstone under union merges.
- [ ] Write standalone, connected, fork/sync, multi-user and adversarial tests.

## Where the code lives

- `convex.lattice.AKeyedRegions`, `LatticeComponent`, `ALatticeApplication`, `ALatticeComponent`, `LatticeHost`,
  `SignedComponent` — the component layer.
- `convex.lattice.LatticeContext`, `OwnerLattice`, `SignedLattice`,
  `JSONLattice`, `LWWLattice`, `IndexLattice`, `MapLattice`, `SetLattice` — lattices
  and write policy.
- `convex.node.NodeServer`, `LatticePropagator` and `RelayHost` (`convex-peer`) — the
  replication runtime and host assembly from node configuration.
- `convex-p2p` (`P2PNode`, `P2PApplication`) — a node assembled up to the root, with
  the application owning everything below it.
- `convex-social` (`Social`, `SocialUser`, `Feed`, `SocialPost`) and `convex-dlfs` —
  worked examples.

## Related

- [CAD045 Lattice Applications](https://docs.convex.world/docs/cad/lattice_apps) — the normative patterns.
- [CAD024 Data Lattice](https://docs.convex.world/docs/cad/data_lattice) and [CAD036 Lattice Node](https://docs.convex.world/docs/cad/lattice_node) — lattice model and hosting.
- [CAD038 Lattice Authorisation](https://docs.convex.world/docs/cad/lattice_auth) — signer authorisation.
- [LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md) and [LATTICE_REGIONS.md](LATTICE_REGIONS.md).

### Atomic mail sends

`Mail.send(envelope)` performs one atomic deposit in the recipient's mailbox.
The deposited lattice entry is the complete send state. There is no separate
outbox or automatic redeposit step; pending envelopes and receipts are read with
`mail.mailbox(recipient).deposited()` and `isReceived(envelope)`.

Prepare signed envelopes before a transaction callback, then use
`mail.transact(draft -> draft.send(envelope))` to include a send in an atomic batch.
All sends commit or abort with the draft. Repeated sends of the same envelope are
idempotent, and a receipt absorbs later deposits at that key. Forks use the same
state and merge rule. Live `Mail.sync()` is a no-op; a fork's sync publishes its
working state. Publication, persistence and receipt are separate from local commit.
