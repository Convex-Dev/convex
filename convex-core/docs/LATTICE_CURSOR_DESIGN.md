# Lattice Cursor Design

Cursors provide atomic access to immutable CVM values. Components own lattice
structure, merge policy, signing policy and publication. Cursors carry no lattice,
shape, context or store, and do not construct missing containers.

This document describes the current implementation. CAD035 describes an earlier
lattice-aware hierarchy and needs updating before this branch's design is normative.
See [LATTICE_APPLICATIONS.md](LATTICE_APPLICATIONS.md) for application assembly,
component-owned working state and batched signing.

## Class hierarchy

```text
ACursor<V>                                get / atomic update / path
└── AForkableCursor<V>                    fork / sync
    ├── Root<V>                           atomic value holder
    │   └── TransactionalRootCursor<V>    opt-in coordinated writes
    ├── PublishingCursor<V>               optional host publication over supplied storage
    ├── PathCursor<V>                     navigation through existing containers
    ├── ProjectionCursor<S,V>             caller-supplied read/write functions
    ├── ForkedCursor<V>                   private copy; explicit overwrite on sync
    ├── TransactionCursor<V>              draft confined to a transaction callback
    └── AUpdateCursor<V,S>                prepare-on-write machinery
        ├── StampedCursor<V>              explicit clock and stamp function
        └── SignedCursor<V>               explicit signing function
AView<T>                                  read-only cursor views
Transactions                              optimistic and locked subtree transactions
```

There are no `Merge`, `Ownership`, `Signing`, `Stamping` or `Shape` interfaces.
Lattice classes retain their domain methods directly. Specialised cursors accept
ordinary Java functions supplied by their owning components.

## Atomic operations and paths

`get`, `set`, `getAndSet`, `compareAndSet`, `getAndUpdate`, `updateAndGet`,
`getAndAccumulate` and `accumulateAndGet` operate on one immutable value. Update
callbacks may retry and must be side-effect free. They receive the actual current
value, including `null`; no empty value is substituted.

`path(keys...)` creates a view without changing state. Reads use `RT.getIn`; writes
use `LatticeOps.assocExisting` to rebuild existing ancestors atomically. All
intermediate containers must already exist. An absent final value can be replaced,
but writing through an absent container throws. `assoc` and `assocIn` follow the same
rule. A plain path write through a signed envelope is refused.

Root CAS uses reference identity. Path, projection and signing views compare the
view by value equality, then make one CAS attempt against the observed underlying
value. A concurrent source change can therefore make their CAS fail even when it
changed an unrelated field. Atomic update operations retry as needed.

Multi-key paths use one `PathCursor`. External key conversion remains a lattice or
component concern; cursors accept canonical CVM keys.

## Component-supplied construction

A component can use `ProjectionCursor.create(source, read, write)` to expose a child
without requiring existing containers. The cursor only executes the supplied
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

`ALatticeComponent.resolve(cursor, lattice, path...)` supplies such functions one
level at a time using the component's lattice. Its write functions use
`LatticeOps.assocIn` to construct an absent container from the lattice's zero.
Unknown missing container types fail; keys never determine container types.
JSON components supply their own map or vector construction functions. No-op writes do not materialise ancestors.
Owner envelopes still require an explicit signing boundary.

Standalone components initialise their roots explicitly:

```java
Root<ASet<ACell>> root = Cursors.root(lattice.zero());
root.updateAndGet(values -> values.include(item));
```

## Forks, transactions and publication

A raw `fork()` holds an independent root and a destination cursor. Its `sync()`
overwrites that destination with the local snapshot; it performs no lattice merge.
A prepared destination value is adopted locally by CAS only if the local snapshot
has not changed meanwhile. Newer local edits remain pending. For an independent
snapshot use `Root.create(cursor.get())`; atomic multi-step edits use `Transactions`.
Cursors retain no creation-time baseline.

Use component working state for CAS-or-merge publication. The component retains
its destination and baseline, reconciles with its own lattice and context, and
publishes through cursor operations. Generic cursors know nothing of that policy.

Live view `sync()` follows its source towards its publication boundary. A host
wraps storage with `PublishingCursor` and installs a callback and a plain binary
reconciliation function using `onSync(publish, merge)`. Only the wrapper allocates
a sync lock. Callbacks are serialised; writes delegate directly to storage, including
its optional transaction lock. `Root.sync()` simply returns the current value.
`RootComponent.cursor()` supplies the host wrapper; use it when descendants need
to reach host publication. No cursor starts a worker or timer.

`Transactions` runs a synchronous callback against a private draft and commits by
CAS, preserving each operation under contention. `TransactionalRootCursor` opts
into a shared write lock for explicit locked execution and contention fallback.
Derived views forward that lock through their existing source links. Typed domain
transactions use `TransactionalComponent.withCursor` to retain component policy.

## Signing and stamping composition

Components can opt into authoring every changed write through a wrapper:

```java
var rule = owners.signingFor(owner);
var unsigned = SignedCursor.create(slot, value -> rule.sign(context, value));
var edits = StampedCursor.create(storage, context::currentTimestamp, lattice::stamp);
```

`AUpdateCursor` applies authoring to all atomic operations. Unchanged views preserve
the existing stored value. Prepared signatures are reused while retries request the
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
for unsigned working state that is signed only when published.

## Code and specifications

- `convex.lattice.cursor`: atomic storage, paths, function-based views, drafts and wrappers.
- `convex.lattice.LatticeOps`: strict path updates and explicit component construction helpers.
- `convex.lattice.ALatticeComponent`, `ALatticeApplication`, `RootComponent`: structure,
  merge policy and publication lifecycle.
- `convex.lattice.LatticeContext`: component signing capabilities and write policy.
- [CAD035 Lattice Cursors](https://docs.convex.world/docs/cad/cursors): earlier cursor model.
- [CAD024 Data Lattice](https://docs.convex.world/docs/cad/data_lattice): lattice specification.
- [CAD038 Lattice Authorisation](https://docs.convex.world/docs/cad/lattice_auth): authorisation.
