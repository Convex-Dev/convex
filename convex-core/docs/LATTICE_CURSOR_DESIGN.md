# Lattice Cursor Design

Cursors provide atomic access to immutable CVM values. Components own lattice
structure, merge policy, signing policy and publication. Cursors carry no lattice,
shape, context or store, and do not construct missing containers.

This document describes the current implementation of CAD035's cursor mechanisms.
See [LATTICE_APPLICATIONS.md](LATTICE_APPLICATIONS.md) for application assembly,
component-owned working state and batched signing.

## Class hierarchy

```text
ACursor<V>                                get / atomic update / persist / path
└── AForkableCursor<V>                    typed paths; fork(); sync(merge)
    ├── Root<V>                           atomic value holder
    │   └── TransactionalRootCursor<V>    opt-in coordinated writes
    ├── ForkedCursor<V>                   working copy; sync reconciles with a plug-in function
    ├── ReadOnlyCursor<V>                 refuses every write; reads, paths and forks pass through
    └── ATransformCursor<V,S>             shared atomic mutable-view operations
        ├── PathCursor<V>                 navigation through existing containers
        ├── ProjectionCursor<S,V>         caller-supplied read/rebuild functions
        └── AUpdateCursor<V,S>            cached preparation per atomic operation
            ├── StampedCursor<V>          explicit clock and stamp function
            ├── SignedCursor<V>           explicit signing function
            └── DefaultCursor<V>          explicit default for absent storage
AView<T>                                  read-only cursor views
Transactions                              optimistic and exclusive subtree transactions
```

There are no `Merge`, `Ownership`, `Signing`, `Stamping` or `Shape` interfaces.
Lattice classes retain their domain methods directly. Specialised cursors accept
ordinary Java functions supplied by their owning components.

The base contract stays minimal: get, atomic update and path at one location.
Subclasses and compositions add behaviour; the base gains no capability hooks that
every subclass and view would have to forward. A service owned by the root gets at
most one hook, resolved through existing source links: `exclusiveLock()`, for
transactions and fork publication, is the only one. Storage, durability and
publication attach at the host, and a module that needs a store mid-write, such as
DLFS writing blob checkpoints, takes it as an explicit parameter. `persist` is not a
hook: it is one concrete method over `updateAndGet` with a store-agnostic
`Persister`, which views inherit and only `ForkedCursor` overrides.

## Atomic operations and paths

`get`, `set`, `getAndSet`, `compareAndSet`, `getAndUpdate`, `updateAndGet`,
`getAndAccumulate` and `accumulateAndGet` operate on one immutable value. Update
callbacks may retry and must be side-effect free. They receive the actual current
value, including `null`; no empty value is substituted.

`get()` returns the cursor's declared value type. Both nested `get(keys...)`
overloads return `ACell`: a child need not have its parent's type. Callers cast or
validate the result explicitly when they require a narrower type.

`get()` and both nested `get(keys...)` overloads are required reads. They throw
`MissingValueException` for an absent entry or a path below nil. A root holding
nil and an entry storing nil are present: reading either returns null.
`getOrDefault(fallback)` handles expected absence without throwing;
`getOrDefault(fallback, keys...)` does the same for a nested path without creating
a cursor. A stored nil does not select the fallback. `exists()` tests presence,
including nil. Each operation reads one snapshot; separate calls need not observe
the same snapshot. Traversal through a scalar, an incompatible Index or sequence
key, or an out-of-range position all mean the addressed value is absent. Required
reads throw `MissingValueException`; optional reads return their fallback, and
presence checks return false.

`path(keys...)` creates a view without reading or changing state. Writes
use `LatticeOps.assocExisting` to rebuild existing ancestors atomically. All
intermediate containers must already exist. An absent final value can be replaced,
but writing through an absent container throws `PathWriteException`, and so does a
write to a container that cannot hold its key, such as a vector given a key that is
not an index within it. The exception carries the keys from the one that does not
fit, and its depth. `assoc` and `assocIn` follow the same rule. A plain path write
through a signed envelope is refused with `IllegalArgumentException`.

Atomic updates keep their creation semantics: a missing final entry is supplied
as null, and a changed result can insert it into an existing map. Unchanged writes
preserve absence. Forks and transactions can likewise start from a missing location
as nil without creating destination state. None of these operations creates a
missing intermediate container implicitly.

Transformations propagate source absence without applying their read function.
A projection over present storage returns the value its supplied function defines,
including nil; it cannot infer entry presence from that function. Time caches retain
presence together with the cached value. `withDefault(value)` explicitly substitutes
for both missing storage and nil, unlike `getOrDefault`, which substitutes only for
absence. Its reads and unchanged writes still create no state.

When migrating callers, retain required reads where absence is a defect. Use
`getOrDefault(null)` only where missing state is expected, or `exists()` when only
presence matters. CVM collection lookups and `RT.getIn` retain their existing rules.

Root CAS uses reference identity. Path, projection and signing views compare the
view by value equality, then make one CAS attempt against the observed underlying
value. A concurrent source change can therefore make their CAS fail even when it
changed an unrelated field. Atomic update operations retry as needed.

Multi-key paths use one `PathCursor`. External key conversion remains a lattice or
component concern; cursors accept canonical CVM keys.

`readOnly()` returns a read-only view of a forkable cursor, which a component can
bind like any other storage. Reads pass through under the same contract. Every
write, even one that changes nothing, throws `ReadOnlyCursorException` before the
source is touched. Paths, defaults and transformed views over it write through it,
so they are refused too, and a component built over it is read-only throughout. A
fork of the view is ordinary writable storage, but its sync publishes through the
view and is refused. Syncing the view itself is refused as well: it can publish
nothing, so a component bound to it fails instead of appearing to publish its
source. Paths and views over it keep the ordinary sync, which only reads. A
transaction that changes nothing commits, since it writes nothing. `AView` subclasses refuse writes with the same exception, an
`UnsupportedOperationException`.

## Persistence

`persist(persister)` replaces the value a cursor holds with the equal value a
`Persister` returns, such as `LatticeHost::persist`, in one atomic update. On an
Etch store the stored cells are then soft references and need not stay in memory.
The cursor holds no store. An edit landing meanwhile repeats the update on the
edited value; persisting again only finds the cells already stored. A fork persists
its working value, then its reconciled baseline outside its lock, replacing the
baseline only if no sync has moved it meanwhile. Views hold no value of their own:
they treat the equal value as unchanged, so the cells are stored and their source
is left to its owner. A read-only view refuses to persist, as it refuses any write.

## Component-supplied construction

For ordinary collections, use `MapComponent.path(key)` or `IndexComponent.path(key)`.
They reject incompatible stored containers and create a correctly typed container
only on a changed write. Their pure `createContainer()` hook can supply initial
contents or refuse creation. Reads and no-op edits leave missing storage absent.

For custom representations, a component can use
`ProjectionCursor.create(source, read, write)` to expose a child without requiring
existing containers. The cursor only executes the supplied
functions; the component's write function chooses what to construct:

```java
var child = ProjectionCursor.create(source,
    parent -> parent == null ? null : parent.get(KEY_CHILD),
    (parent, value) -> {
        ACell previous = parent == null ? null : parent.get(KEY_CHILD);
        if (Utils.equals(previous, value)) return parent;
        return (parent == null ? PARENT_LATTICE.zero() : parent)
            .assoc(KEY_CHILD, value);
    });
```

The functions must be pure, preserve unrelated source state, and obey the view
contract: reading a written value returns that value, and writing the current view
back leaves the source unchanged by value equality. Construction and the child edit
commit in the same atomic source update. Exceptions commit nothing. Obtaining a view
or reading it has no state-changing effects.

`cursor.withDefault(emptyContainer).path(key)` supplies a known container type
without any merge definition. The optional adapter helper
`LatticeComponent.resolve(cursor, lattice, keys...)` builds a path cursor for each
lattice key, using an explicit lattice. It reads as any path does, so a missing
key is absent. Its writes use `LatticeOps.assocIn` to construct an absent
container from the lattice's zero at that level.
Unknown missing container types fail; keys never determine container types.
JSON value components construct nothing; a parent needing construction supplies it. No-op writes do not materialise ancestors.
Owner envelopes still require an explicit signing boundary.

Standalone components initialise their roots explicitly:

```java
Root<ASet<ACell>> root = Cursors.root(lattice.zero());
root.updateAndGet(values -> values.include(item));
```

## Forks, transactions and publication

`fork()` creates an isolated working copy, a `ForkedCursor`, retaining its
destination and reconciliation baseline. Each `sync(merge)` supplies the policy for
that publication: an unchanged copy adopts the destination, an unchanged destination
accepts the working value, and divergent changes invoke the supplied function.
The fork retains no merge callback. Ordinary roots, paths and transformed views
implement `sync(merge)` as a read-only no-op without invoking that function or
publishing any enclosing fork; a read-only view refuses it.

The destination has the same value type as the fork. Signing and stamping compose
through cursor views, and where an authoring view sits relative to the fork decides
when it applies. A fork of an authoring view keeps unauthored working state and
authors once, on publication: `SignedCursor.create(slot, signer).fork()` signs at
sync, and a fork of a `StampedCursor` stamps at sync. An authoring view built over a
fork instead authors each local edit. The component chooses this order through what
it binds: `fork()` forks bound storage and rebinding rebuilds the views above it, so
an authoring view within bound storage applies on publication, and one the component
builds over its storage applies to each edit. Transaction drafts follow the same rule.

A fork inside a stamp reconciles before publication stamps it, so its working value
still carries the timestamp it was forked from. When both sides changed, a
newest-timestamp merge selects the destination and discards the fork's edits,
however recent. The publication merge of such a component treats its own change as
the newer, since publication stamps it. A fork outside the stamp carries the time of
its last edit, and its publication merge can use the record's ordering unchanged.

Each fork uses an independent `TransactionalRootCursor`. Sync holds its existing
write lock while the destination's atomic update reconciles and commits by CAS.
Fork reads take the same lock. A successful commit installs the reconciled working
value and advances the baseline without further user code or merging. Failed
reconciliation or preparation commits nothing; competing destination writes remain
independent. Destination write functions and the merge must not write or sync the same fork,
or wait for operations on it. This is a callback contract, without thread ownership
or re-entrancy guards.

A component with an independent publication boundary owns such a fork and supplies
its own reconciliation rule; `ALatticeComponent.mergePublication(own, other)` is
that component's merge, called only when both sides changed. No `ALattice` is
required. `LatticeComponent` adapts a lattice for components that choose to use one. An application overrides it
to take regions that its own components publish from the destination; each such
component publishes through its own fork of the host.
For a detached snapshot use `Root.create(cursor.get())`; atomic multi-step edits use
`Transactions`.

`LatticeHost.cursor()` is ordinary storage. Call the owning component's `sync()` to
publish; it updates only its destination and never publishes that cursor further.
The component which supplied the destination owns the next publication step, and
the host's own `sync()` persists and replicates. Lightweight components have no
independent publication work. No cursor starts a worker or timer.

Component constructors bind their supplied working cursor directly. The protected
`rebind(cursor)` hook recreates the same domain operations, configuration and child
views over new storage, without creating another fork. A component's `fork()`
rebinds to a fork of its bound storage. Concrete subclasses can refine the return
type without adding self-type parameters to the component hierarchy.

The base component retains one bound `storage` cursor. Its overridable `cursor()`
exposes the editing view, which may stamp local changes. Sync always uses bound
storage, so an editing-view override cannot hide the publication boundary. Wrappers
bound to the same fork share publication; a path or transformed view over that fork
has no publication of its own. Sync returns the bound value, which may be nil even
when the editing view supplies a default. Reads and ordinary view sync never author
state. Applications may add preparation and managed-child publication explicitly.

`Transactions` runs a synchronous callback against a private draft and commits by
CAS, preserving each operation under contention. `transact` returns the result of
the run that committed; `execute` returns nothing. `TransactionalRootCursor` opts into a shared write lock for
exclusive execution, where the callback runs once under the lock, and for contention
fallback. Derived views forward that lock through their existing source links. Typed domain
transactions use `TransactionalComponent.withCursor` to retain component policy;
components can delegate that public adapter to their protected `rebind` hook.
`TransactionalComponent.exclusive()` gives an `ExclusiveComponent`, built from bound
storage as a fork is: a read-only view of it, whose transactions rebind the domain
component to a draft and commit to that storage under its root's lock.
The transaction targets bound `storage()`, while the rebound view rebuilds the
component's editing views over the draft. An authoring view within bound storage
applies once at commit: a signing view signs the committed value, and a stamp there
gives it the commit's time. A stamp the component builds over its storage applies
within the draft instead, with the time of the edit, and commit does not apply it
again. Unsigned staged components defer signing to publication.
Each attempt uses a fresh `TransactionalRootCursor` for its draft, supporting
concurrent edits and nested transactions through the existing cursor machinery.
The callback must finish its work before returning. Only the captured immutable
value is committed; later edits to a retained draft or its forks remain private.

## Signing and stamping composition

Components can opt into authoring every changed write through a wrapper. The
standard `Timestamped` component owns both the stamp-on-write cursor and the
ordering used for incoming records. Its `cursor()` is a `StampedCursor`; its merge
uses the underlying storage and preserves incoming timestamps. Enclosing signed
or staged boundaries share its lattice for ordering without re-stamping. The
component creates no additional publication boundary.

For custom composition, the underlying cursor mechanisms remain available:

```java
var rule = owners.signingFor(owner);
var unsigned = SignedCursor.create(slot, value -> rule.sign(value, signer));
var edits = StampedCursor.create(storage, getTimestamp, lattice::stamp);
```

`ATransformCursor` supplies the atomic operations for paths, projections and authored views.
It reads a view value, rebuilds the source on a changed write, and preserves the
source on a no-op. Returned values reflect the source's write policy, including
stamping. Read functions must accept the values that policy can produce.

A transform behaves as any cursor does for its consumers. It is absent where it
finds no value, including wherever its source is absent; a view that should hold a
value when nothing is stored is built over `withDefault`. A transform creates no
storage by default: a navigating view refuses a missing container with
`PathWriteException`. A transform whose definition knows how to build its storage
builds it in its write: a default from its default, an authoring view its envelope
or record, a component's projection what its write function supplies.

`ProjectionCursor` rebuilds from the current source on retries, preserving siblings.
`AUpdateCursor` adds caching for prepared replacements. Prepared signatures are reused while retries request the
same value; a genuinely different payload must be signed again. `StampedCursor`
resolves its clock once per changed operation, reusing that timestamp across retries.
Writes through descendant views pass through the same authoring boundary atomically.

`SignedCursor` unwraps `SignedData` on reads and uses the bound function on writes.
A null signing result fails the write. The component binds the owner lattice's rule
so local authoring uses the same authorisation as incoming verification.

`StampedCursor` preserves the value type and injects the supplied timestamp. It
cannot distinguish local edits from incoming state: the component retains the
underlying cursor when incoming merges must preserve their original timestamps.
Neither wrapper merges values or controls publication timing. Use `SignedComponent`
for unsigned working state that is signed only when published, and
`ALocalComponent` for a service's stamped record in `:local`.

## Code and specifications

- `convex.lattice.cursor`: atomic storage, paths, function-based views, drafts and wrappers.
- `convex.lattice.LatticeOps`: strict path updates and explicit component construction helpers.
- `convex.lattice.ALatticeComponent`, `ALatticeApplication`, `LatticeHost`: structure,
  merge policy and publication lifecycle.
- `convex.lattice.LatticeContext`: optional policy for lattice adapters; domain
  components bind their own signing and clock functions.
- [CAD035 Lattice Cursors](https://docs.convex.world/docs/cad/cursors): earlier cursor model.
- [CAD024 Data Lattice](https://docs.convex.world/docs/cad/data_lattice): lattice specification.
- [CAD038 Lattice Authorisation](https://docs.convex.world/docs/cad/lattice_auth): authorisation.
