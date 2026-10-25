# Lattice application design

A lattice application defines how shared data is edited, accepted and merged.
It can run locally, retain state in a store, or replicate through a node. These
choices should not change its domain operations.

This document explains the design and its boundaries. Method contracts belong in
source Javadocs; runnable assemblies belong in the [examples](#examples).
[CAD045](https://docs.convex.world/docs/cad/lattice_apps) is the normative reference.

## Responsibilities

| Part | Responsibility |
|---|---|
| Value | Immutable application state, including any signed envelopes |
| Merge rule | Decide how two versions of a value combine |
| Cursor | Atomic access to a value or a position within it |
| Component | Domain operations, acceptance policy and publication at one boundary |
| Application | Compose components and choose what incoming state to receive and when to publish |
| Host | Hold the published root and provide retention and durability operations |
| Node | Deliver and replicate published state under configured network policies |

A component binds storage to meaning. Its cursor provides atomic operations;
its domain logic supplies container creation, validation, signing, timestamps
and merge behaviour. Reusable lattice classes can supply merge rules, but an
application does not need a second object tree mirroring its components.

The host knows neither domain paths nor merge rules. It delivers incoming values
to registered applications, which decide what to accept. With no receiving
applications, a host ignores incoming state. Networking wraps the same host and
uses the same delivery boundary.

Clocks, signers and authority resolvers are explicit component dependencies.
They are retained when constructing private views; they are not inherited from
a host or discovered through a cursor.

## Data and merge boundaries

Design the state tree around independently mergeable values. Choose a rule for
each boundary: union for additive collections, a register for whole-value
replacement, or a domain-specific merge. Components sharing a region must agree
on its representation and rules. Reuse definitions from the
[region catalogue](LATTICE_REGIONS.md).

The boundary determines what concurrent edits can preserve. A set inside a
last-write-wins record is part of that record: competing versions select one
whole record, even if the local editing API offers set operations. A union set
instead retains additions from either side. Removing an entry under union is
not a replicated deletion; use a tombstone or a newer enclosing register value.

Merge should be associative, idempotent and commutative where the domain permits.
The current timestamp rules retain the local value on an exact tie. Different
values with equal timestamps can therefore remain different across replicas;
authoring policy must supply ordering where an update must supersede another.
There is no hidden logical clock that repairs ties.

Local updates preserve fields they do not understand. Absence can justify
creating a container; an unfamiliar representation cannot. A relay may verify
an envelope and its ordering metadata without understanding every payload field.
A domain view can reject an unsupported operation while still retaining and
replicating that value.

## Composition and sharing

An application usually groups named regions, each handled by a component. Region
paths belong to this assembly; domain components receive storage at their own
position. The same component can therefore live at a default region, a custom
path, or inside another application.

A component can be live in its supplied storage or hold a staged working copy.
Live edits are immediately visible there. Staged edits become visible at their
destination only when the component publishes. This choice belongs to the
component's construction and is independent of whether its host has networking.

Several applications can share a host without sharing private working state.
They publish only the regions they own and preserve unrelated regions. Sharing
the same component instance also shares its pending changes; either application
can publish them. Separate staged components at the same path reconcile their
independent copies when publishing.

A regions application routes incoming values and publishes its children. It has
no separate aggregate copy to overwrite the container afterwards. Publishing
children is sequential, not atomic across regions: earlier successful work
remains if a later child fails. An operation needing an atomic aggregate belongs
at a common ancestor that owns that aggregate.

## Publication and delivery

State crosses distinct boundaries:

```text
private edits -> component destination -> published host root
                                              |
                                    retention and replication
                                              |
                                       durability barrier
```

Each publication stops at its immediate destination. Publishing a child does
not publish an enclosing draft; publishing an application does not save the host.
The host publishes only state already present in its root. It never implicitly
accepts outstanding application or user drafts.

Incoming delivery is a separate operation. A receiving application can merge
into live state, keep the result staged, or ignore it. Delivery does not invoke
application publication. An acknowledgement of delivery therefore does not
promise that private results have been published or saved.

Root and path delivery must enforce the same merge and authorisation rules.
Routing cannot bypass an enclosing signed boundary: a remote sender supplies a
complete envelope, not an unsigned update to a field inside it. Domain views
that only provide local operations need an explicit receiving component when
they are exposed to replication.

Delivery to several applications is sequential and can partially succeed.
Receiving merges must tolerate duplicate delivery; failures do not undo earlier
applications' changes. Registration itself neither replays previous deliveries
nor publishes pending edits.

## Forks and transactions

A component fork is a reusable private working copy. Publication compares it
with its destination and their last reconciled value. If only one side changed,
that side is adopted. If both changed, the component's merge rule reconciles
them. Local edits occupy the local side of directional tie-breaking rules.
Reconciliation and signing must succeed before the destination changes.

A transaction instead groups operations into one local atomic change. If the
live value changes before commit, the operations run again against current
state. This matters when both operations must survive: two independent edits to
a whole-value register may select one result, while retrying transactions can
apply both operations in sequence.

Transaction callbacks operate only on their private view and must be safe to
retry. External effects happen after commit. Decisions spanning several paths
belong in a transaction at their common ancestor. Separate component calls and
separate publications are not one transaction. Local commit does not promise
replication or durability.

A host fork has a different purpose: it is a detached host with its own
applications and optional store. It has no host-level merge rule. Synchronising
it exchanges roots through each host's normal application delivery, like two
nodes without a network. Saving a private host does not perform that exchange.
Its store must be separate from the origin's; reopening saved state does not
restore an implicit relationship with the origin.

## Ownership and authoring

An owned region maps identities to signed values. The identity may be a public
key, a DID or another identifier interpreted by the region's authority policy.
The cryptographic signature proves which key signed; the bound authorisation
rule decides whether that key may write for this owner. Several keys can be
authorised for one identity.

Verification and signing are separate capabilities. A replica can verify and
retain an existing envelope without a private key. If merging would create a
new payload, an authorised signer must produce its envelope. Without that
capability, the public merge retains the established value rather than inventing
a signature. The owner is responsible for reconciling divergent signed state.

Local authoring uses the same owner and payload rules as incoming acceptance.
A signer cannot change the requested payload or substitute its own authority
policy. Signed editing views keep this enforcement around ordinary domain
operations. Unsigned drafts allow a batch to be signed once when it reaches its
signed destination; intermediate drafts remain private.

Clocks apply to local writes. Incoming values retain their signed timestamps;
merging does not re-stamp them. Resolve authority data outside atomic operations
so verification uses locally available information. Updating that information
affects later checks, without retroactively deleting previously accepted state.

Compatibility diagnostics are not authorisation. A component may recognise the
shape of a candidate without accepting its signature, owner or contents. Merge
must enforce acceptance even if no diagnostic was called. Raw cursor access
remains trusted application code; component boundaries are not a sandbox between
code sharing a process.

## Retention, durability and networking

The host can have no store, a memory store or a durable store. Opening restores
retained state before applications are constructed. Starting afresh over existing
state must be explicit. The service owns the store's lifetime; applications and
network attachments borrow it.

Writing immutable cells, retaining a root and completing a durability barrier
are separate operations. Cells written without a retained root are not a promise
of recovery. A checkpoint retains one host snapshot and completes the store's
barrier. It excludes private application drafts and writes arriving after that
snapshot. A failed barrier does not roll back publication already performed.

The service chooses when to promise durability. It can checkpoint before
acknowledging a durable request or batch flushes when delayed durability is
acceptable. Background flushing processes already-retained state and must expose
failures. Service shutdown stops writers and networking, completes its chosen
saving policy, then closes the store.

Networking does not decide that saving policy. Attaching or closing replication
neither publishes application drafts nor supplies a durability guarantee. A host
and its applications remain usable after networking closes. Propagation groups
choose what to serve and whom to exchange with; delivery still passes through
the application's acceptance rules.

Large-data components such as DLFS can write immutable blob branches to an
explicitly supplied store during editing. This limits memory use without
publishing the file tree. Abandoned edits may leave unreferenced cells, but do
not change retained application state.

See [persistence](../../convex-peer/docs/PERSISTENCE.md) and
[lattice networking](../../convex-peer/docs/LATTICE_NETWORKING.md) for the runtime
contracts. Runtime subscription and observation APIs remain future work; they
are not part of the current application model.

## Examples

These executable assemblies and tests exercise the design boundaries.

| Scenario | Source |
|---|---|
| Signed user records, local edits and private drafts | [FavouritesExample](../src/test/java/convex/lattice/app/FavouritesExample.java) |
| Atomic edits versus mergeable drafts | [FavouritesTransactionTest](../src/test/java/convex/lattice/app/FavouritesTransactionTest.java) and [TaskBoardApplicationTest](../src/test/java/convex/lattice/app/TaskBoardApplicationTest.java) |
| Shared regions and independent publication | [SharedRegionsTest](../src/test/java/convex/lattice/app/SharedRegionsTest.java) |
| DID ownership and several authorised keys | [OwnedScoresApplicationTest](../src/test/java/convex/lattice/app/OwnedScoresApplicationTest.java) |
| Saving, reopening, batched durability and private host storage | [FavouritesLifecycleTest](../src/test/java/convex/lattice/app/FavouritesLifecycleTest.java) |
| Replication and staged receipt | [ReadingListApplicationTest](../../convex-peer/src/test/java/convex/node/app/ReadingListApplicationTest.java) |
| DLFS custom paths, DID owners and failed durable requests | [DLFSLifecycleExampleTest](../../convex-dlfs/src/test/java/convex/dlfs/test/DLFSLifecycleExampleTest.java) |
| Several domain applications on one host | [SharedApplicationsExample](../../convex-integration/src/test/java/convex/lattice/SharedApplicationsExample.java) |

## References

- [Component Javadocs](../src/main/java/convex/lattice/ALatticeComponent.java),
  [application Javadocs](../src/main/java/convex/lattice/ALatticeApplication.java)
  and [host Javadocs](../src/main/java/convex/lattice/LatticeHost.java).
- [Cursor design](LATTICE_CURSOR_DESIGN.md) and
  [design history](LATTICE_DESIGN_LOG.md).
- [CAD024: merge semantics](https://docs.convex.world/docs/cad/data_lattice),
  [CAD035: cursors](https://docs.convex.world/docs/cad/cursors),
  [CAD038: authorisation](https://docs.convex.world/docs/cad/lattice_auth) and
  [CAD036: replication](https://docs.convex.world/docs/cad/lattice_node).
