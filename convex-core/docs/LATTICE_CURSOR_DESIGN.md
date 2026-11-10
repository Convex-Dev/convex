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
    ├── Root<V>                           atomic value holder; at most one claim
    ├── ForkedCursor<V>                   working copy; sync reconciles with a plug-in function
    ├── ReadOnlyCursor<V>                 refuses every write; reads, paths and forks pass through
    └── ATransformCursor<V,S>             shared atomic mutable-view operations
        ├── PathCursor<V>                 navigation through existing containers
        ├── ProjectionCursor<S,V>         caller-supplied read/rebuild functions
        ├── StampedCursor<V>              explicit clock and stamp function
        ├── SignedCursor<V>               explicit signing function
        └── DefaultCursor<V>              explicit default for absent storage
AView<T>                                  read-only cursor views
Transactions                              optimistic and exclusive subtree transactions
```

There are no `Merge`, `Ownership`, `Signing`, `Stamping` or `Shape` interfaces.
Lattice classes retain their domain methods directly. Specialised cursors accept
ordinary Java functions supplied by their owning components.

The base contract stays minimal: get, atomic update and path at one location.
Subclasses and compositions add behaviour; the base gains no capability hooks that
every subclass and view would have to forward. A service owned by the root gets at
most one hook, resolved through existing source links: `claim()`, for
transactions and fork publication, is the only one. Storage, durability and
publication attach at the host, and a module that needs a store mid-write, such as
DLFS writing blob checkpoints, takes it as an explicit parameter. `persist` is not a
hook: it is one method with a store-agnostic `Persister`, which views inherit and
only the cursors that hold a value, `Root` and `ForkedCursor`, override.

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

A cursor that cannot be written refuses every write, whatever the values: a write
that would change nothing, a compare-and-set that would not match and a
transaction that changes nothing are refused like any other. A read-only view
throws `ReadOnlyCursorException`, and a path with nowhere to write throws
`PathWriteException`. The refusal is decided on the value the write would land on
and leaves it untouched, so a fork whose container has gone is refused at sync and
keeps its edits. Where a write could land, as at a missing final entry or under a
view that constructs its container, a write that changes nothing stays silent and
creates nothing. A value the cursor will not accept, such as one no signer is
authorised for, is refused only when it would be written.

Transformations propagate source absence without applying their read function.
A projection over present storage returns the value its supplied function defines,
including nil; it cannot infer entry presence from that function. Time caches retain
presence together with the cached value. `withDefault(value)` explicitly substitutes
for both missing storage and nil, unlike `getOrDefault`, which substitutes only for
absence. Its reads and unchanged writes still create no state.

When migrating callers, retain required reads where absence is a defect. Use
`getOrDefault(null)` only where missing state is expected, or `exists()` when only
presence matters. CVM collection lookups and `RT.getIn` retain their existing rules.

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
transaction is a write, so one that changes nothing is refused as well. `AView` subclasses refuse writes with the same exception, an
`UnsupportedOperationException`.

## Values and the cells that hold them

A value is what CAD3 equality sees. The same value can be held as different Java
objects: cells built by an edit reach their children through strong references,
and the equal cells a store returns reach theirs through soft ones. Which is held
decides only memory use, and cursors keep the two apart with four rules.

1. **Every operation decides by value.** Compare-and-set, an unchanged write, a
   conflict, a claim and a fork's test of which side changed all compare values, on
   a root, a fork and a view alike. No result depends on which object holds a
   value.
2. **A write of an equal value keeps the cells held.** It changes nothing, signs
   and stamps nothing, and cannot displace persisted cells with a copy built
   elsewhere.
3. **Only a holder switches cells for equal ones.** A `Root` holds a value, and a
   fork holds two, its working value and its baseline; a view holds none. `persist`
   is the switch, and a fork also adopts its destination's cells at sync. Neither is
   a write, so rule 2 does not stop them and a claim does not delay them.
4. **A changed write is built on the cells held when it lands, where the cursor
   has the function to run.** An update function, at a root or through any view,
   runs again if the cells were switched beneath it, so what it leaves untouched
   stays persisted. Compare-and-set and a transaction commit bring a finished
   value for the cursor's own place, which goes in as given; through a view, the
   source around that place is still built on the cells held.

`Root.install` is the one place a changed write lands and the cursors' only test of
object identity; whatever fails there looks again by value. Component code that
edits through update functions needs no such test. The exception is work prepared
outside an update function because it does I/O, as DLFS prepares and checkpoints
file content: it is built on the cells it read, so it goes in only over those very
cells and is prepared again otherwise. That test decides whether work is redone,
never what the result is.

Compare-and-set is the atomic update "if the value equals the expected one then
the new value, otherwise unchanged" on every cursor, and its result says whether
the value matched. It therefore fails only when the cursor's own value differs:
a change elsewhere in a view's source makes the update run again, as it does for
any update function. The contract is about values alone, so a cursor that signs,
stamps or merges holds what that makes of the new value.

Rule 2 costs one comparison per write: the same object is recognised at once, and
otherwise the comparison hashes the branch cells on the changed path, which are
kept and needed again when the value is saved ([Equality](EQUALITY.md)).

A finished value built before a persist landed puts the cells it was built on
back, so they stay in memory until the holder is next persisted. Prefer an update
function for an edit to a large value.

## Persistence

Persisting a value builds new cells and leaves the ones it was given as they are.
On an Etch store the new cells reach their children through soft references, so
stored data can leave memory; the original cells keep strong references to theirs.
The two values are equal, so only memory use tells them apart. Stored data is
released only once whoever holds the value has switched to the new cells: a holder
left on the original keeps the whole tree in memory, however much of it is stored.

`persist(persister)` makes that switch for a cursor, replacing the cells it holds
with the equal ones a `Persister` returns, such as `LatticeHost::persist`, in one
atomic step. The cursor holds no store. An edit landing meanwhile repeats the
persist on the edited value; persisting again only finds the cells already stored.
After three attempts the cursor keeps the value as it stands, so writes that never
pause cannot hold a persist up, and a later persist switches what is then held. A
fork persists its working value, then its reconciled baseline without taking a claim,
replacing the baseline only if no sync has moved it meanwhile. Views hold no value of
their own: to them the persisted value is a write of an equal value, so the cells are
stored and their source is left to its owner. A read-only view refuses to persist, as
it refuses any write.

Saving a value switches nobody else. A fork is separate storage, so a host sync that
saves its destination leaves the fork on the cells it already held. A long-lived fork
keeps what is written to it in memory until it is persisted, which a component given
a persistence function does at each sync, or until it syncs with no edits since its
last sync and so adopts the saved value. The host root switches as part of its own
sync, even when a write
[lands during the save](../../convex-peer/docs/PERSISTENCE.md#ordering).

## Component-supplied construction

For ordinary collections, use `MapComponent.path(key)` or `IndexComponent.path(key)`.
An entry reads as a path does: a missing key is absent and a stored nil is present.
They reject incompatible stored containers and create a correctly typed container
only on a changed write. Their pure `createContainer()` hook can supply initial
contents or refuse creation. Reads and no-op edits leave missing storage absent.

For custom representations, a component can use
`ProjectionCursor.create(source, read, write)` to expose a child without requiring
existing containers. The cursor only executes the supplied
functions; the component's write function chooses what to construct. A projection
is present wherever its source is, so the child below reads as nil when its key is
missing. A child whose absence matters is a transform that reports it from
`viewValue`, as the collection entries do:

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
Both tests are by value, so a destination persisted since the last sync counts as
unchanged, and accepting the working value replaces its persisted cells with the
fork's. A fork persisted first hands over persisted cells; otherwise the destination
holds the fork's cells strongly until it is next saved.
`reconcile(merge)` is the same with a `ThreeWayMerge`, which also receives the value
both sides last agreed on. The fork retains no merge callback. Ordinary roots, paths
and transformed views implement both as a read-only no-op without invoking that
function or publishing any enclosing fork; a read-only view refuses them.

The destination has the same value type as the fork. Signing and stamping compose
through cursor views, and where an authoring view sits relative to the fork decides
when it applies. A fork of an authoring view keeps unauthored working state and
authors once, on publication: `SignedCursor.create(slot, signer).fork()` signs at
sync, and a fork of a `StampedCursor` stamps at sync. An authoring view built over a
fork instead authors each local edit. The component chooses this order through what
it binds: `fork()` forks bound storage and rebinding rebuilds the views above it, so
an authoring view within bound storage applies on publication, and one the component
builds over its storage applies to each edit. Transaction drafts follow the same rule.

A fork that takes part in timestamp-ordered merges sits outside its stamp. Inside
it, the fork's working value keeps the timestamp it was forked from, so incoming
merges and reconciliation at sync select older records over its edits.

Each fork uses an independent `Root`. Sync holds the claim on all of it while the
destination's atomic update reconciles and commits by CAS.
Fork reads never wait: a read during sync sees the working value from before it,
and a read after sync returns sees the published value. A successful commit installs the reconciled working
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
A component can override `mergePublication(base, own, other)` instead, to use the
value both sides last agreed on; by default that form calls the two-value one.
`LatticeOps.mergeChanges` is an experimental rule for it, for a merge with no better
way of handling conflicts. A merge that can resolve a conflict itself, as a lattice
join does, should do that instead. Each side's changes are kept, so changes to
different locations never conflict, and it throws `MergeConflictException` where
both sides changed one location to different values.
For a detached snapshot use `Root.create(cursor.get())`; atomic multi-step edits use
`Transactions`.

`LatticeHost.cursor()` is ordinary storage. Call the owning component's `sync()` to
publish; it updates only its destination and never publishes that cursor further.
The component which supplied the destination owns the next publication step, and
the host's own `sync()` persists and replicates. Lightweight components have no
independent publication work. No cursor starts a worker or timer.

Component constructors bind their supplied working cursor directly. `rebind(cursor)`
makes the same component over new storage, without creating another fork: a
component's `fork()` rebinds to a fork of its bound storage, and a transaction
rebinds to each attempt's draft. Only the cursor's value commits or publishes. The
component decides what happens to everything else it holds: a reference for what is
only read or safe to reuse, its own copy for what it changes, and child components
made on the new cursor when they are needed. Region containers keep their factories
and make children on demand, so a rebound container's children are on its own
cursor. Concrete subclasses can refine the return type without adding self-type
parameters to the component hierarchy.

The base component retains one bound `storage` cursor. Its overridable `cursor()`
exposes the editing view, which may stamp local changes. Sync always uses bound
storage, so an editing-view override cannot hide the publication boundary. Wrappers
bound to the same fork share publication; a path or transformed view over that fork
has no publication of its own. Sync returns the bound value, which may be nil even
when the editing view supplies a default. Reads and ordinary view sync never author
state. Applications may add preparation and managed-child publication explicitly.

`Transactions` runs a synchronous callback against a private draft and commits by
CAS, preserving each operation under contention. `transact` returns the result of
the run that committed; `execute` returns nothing.

Optimistic execution is the fast path for transactions that do not collide. Commit
checks only the transaction's own value: a change elsewhere under the root never
reruns the callback, and its finished result is applied to the new root. When its own
value has changed twice, the transaction takes the root's claim and finishes under
it, so transactions that collide run one at a time and a callback runs at most three
times. An exclusive transaction starts with the claim and runs once, for work too
expensive to repeat or with side effects.

A root holds at most one claim, so claims queue whatever they cover. A claim covers
what the claiming cursor reads: each derived view composes its own read into the
claim through its source link, and the root needs to know nothing about paths or
views. While a claim is held, a write that would change that part of the value, or
leave it unreadable to the claiming view, waits for the release and recalculates, and
a write that leaves it equal carries on. Persisting leaves the whole value equal, so it
proceeds during a claim and the root then holds the persisted cells; an exclusive
transaction that changes nothing writes nothing over them. A transaction that does change the value commits cells built
from the value it started with, so cells persisted meanwhile are replaced until the
next save. Reads never wait. Without a claim, each read or compare-and-set of the root adds
one type check, and nothing is recorded per transaction.

Typed domain
transactions use `rebind` to retain component policy; a component opts in by
declaring `TransactionalComponent` and refining `rebind` to return its own type.
`TransactionalComponent.exclusive()` gives an `ExclusiveComponent`, built from bound
storage as a fork is: a read-only view of it, whose transactions rebind the domain
component to a draft and commit to that storage under its root's claim.
The transaction targets bound `storage()`, while the rebound view rebuilds the
component's editing views over the draft. An authoring view within bound storage
applies once at commit: a signing view signs the committed value, and a stamp there
gives it the commit's time. A stamp the component builds over its storage applies
within the draft instead, with the time of the edit, and commit does not apply it
again. Unsigned staged components defer signing to publication.
Each attempt uses a fresh `Root` for its draft, supporting
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
Signed and stamped views also rebuild on each attempt: a retried write signs again,
and `StampedCursor` reads its clock again, so a write that loses a race is stamped
no earlier than the write it lost to.
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
- [CAD035 Lattice Cursors](https://docs.convex.world/docs/cad/cursors): the cursor rules this implements.
- [CAD024 Data Lattice](https://docs.convex.world/docs/cad/data_lattice): lattice specification.
- [CAD038 Lattice Authorisation](https://docs.convex.world/docs/cad/lattice_auth): authorisation.
