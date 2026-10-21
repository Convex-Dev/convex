# Lattice design change log

A running log of the design changes made or proposed on branch
`codex/lattice-application` that diverge from the CADs as written. The code on the
branch is the experimental implementation; nothing here is normative until the CADs
say so. Once a change is agreed, the CAD sections it lists are updated together and
the entry is marked accordingly.

Each entry records what changed, where it is implemented, which CAD text it
contradicts or extends, the wording that would replace it, and any decision still
open. Section names refer to headings in the CAD sources
(`docs/cad/024_data_lattice`, `035_cursors`, `045_lattice_apps` in the `design`
repository).

Status values: **Implemented** (on the branch, passing the full build),
**Decision needed** (implemented one way, alternative still open), **Agreed**,
**CAD updated**.

## Current API note

**CAD scope alignment, 18 October 2026:** the local `design` checkout now separates
CAD024's abstract lattice semantics and CAD038's authorisation rules from CAD045's
component implementation. CAD035 describes the supporting cursor mechanisms;
CAD036 covers delivery and replication; CAD053 registers shared region names.
The affected sections now describe explicit component boundaries, owned-map
verification, construction and signing views, and fork reconciliation without
automatic cursor policy inheritance. These are local CAD source changes, not a
claim that the published documentation has been deployed. Earlier entries below
remain the history of design decisions; this note does not close unrelated open
questions or assert that every older CAD discrepancy has been resolved.

The entries below record earlier design steps and may name APIs superseded by the
current branch. See [LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md) and
[LATTICE_APPLICATIONS.md](LATTICE_APPLICATIONS.md) for the current API:

- Cursors carry no lattice, context or container-construction policy. Components
  supply read/write functions and explicit signing/stamping functions.
- Ordinary roots hold only current atomic state. A fork takes its merge function
  when created and is the only cursor with a `sync()`; `LatticeHost.sync()` publishes
  host storage under its own lock. The old `Root.onSync`, `Root.view` and
  `PublishingCursor` are removed.
- `detach`/cursor `merge` and creation-time snapshots are removed. Transactions
  retain their attempt input; component publication retains its own baseline.
- Domain forks own their publication boundary directly. There is no hidden
  component with a separate context behind a domain fork's cursor.
- `StampingLattice` is removed. `StampedCursor` takes the component's stamp function.

These are application API changes; encoding and CVM execution are unchanged.

## 1. Components place write boundaries; lattices construct no cursors

**Status:** Implemented (`0847c923c`).

The component that owns an owner-keyed level wraps each owner's slot with
`SignedCursor.forOwner(slot, owners, owner)`, taking the signing rule from the owner
lattice so authoring uses exactly the rule the host verifies with. The component that
owns a stamped level wraps it with `StampedCursor.create(base, stamping)`. Navigation
inserts nothing, and lattices have no cursor hooks.

CAD text affected:

- CAD024 *Cursors*: "when a Signed Lattice boundary is crossed, cryptographic signing
  MUST be enforced transparently."
- CAD024 *Signed Lattice* paragraph: signing on write enforced by the cursor layer.
- CAD045 *Application Architecture*: the layer table gives the cursor chain
  "transparent signing".
- CAD045 *Signing Boundary*: "The cursor system inserts a Signed Cursor automatically
  when `path()` crosses a Signed Lattice boundary."
- CAD045 *Local Trust vs Network Verification*: "A cursor allows any write to any
  path."

Proposed wording: signing is enforced at the owner boundary by the signing cursor the
owning component wraps around the slot, using the owner lattice's own rule; navigation
never crosses a boundary on its own; a plain write through a signed envelope is
refused. Applications still never sign values themselves.

## 2. Virtual `:value` path key removed

**Status:** Implemented (`0847c923c`).

Reaching an owner's unsigned value by navigating a `:value` key is gone. The owner
slot holds `SignedData`; the component crosses it with the signing cursor. A plain
path can still read through the envelope, but `LatticeOps` refuses to write through
`SignedData`, because a record's `assoc` would silently degrade it to a map and drop
the signature.

The lattice schema kept a `:value` branch below `SignedLattice` as a description for
tools; nothing used it. On 17 October the lattice tree was made to end at the
envelope, so the merge tree and the schema tree are one tree, and a path inside an
envelope is outside the served lattice. The owning component navigates the unsigned
value with the owner lattice's value lattice.

CAD text affected: the CAD035 Java example and the CAD045 *Cursor Chain* diagram both
navigate `Keywords.VALUE`.

## 3. Cursors carry no lattice

**Status:** Implemented (`c1bfed931`).

`ALatticeCursor`, `RootLatticeCursor`, `DescendedCursor` and `ForkedLatticeCursor`
are removed. A cursor is a location with atomic update, navigation and an inherited
write-policy context. Everything a lattice used to supply arrives as a parameter from
the component that owns the level, through five interfaces in `convex.lattice`:
`Shape` (zero, structural flag), `Merge`, `Signing`, `Ownership` and `Stamping`.
Every `ALattice` is a `Shape` and a `Merge`; `SignedLattice`, the owner lattices and
`StampingLattice` implement the other three. The cursor package imports nothing else
from the lattice packages, so the dependency between the two is acyclic in both
directions.

CAD text affected:

- CAD024 *Cursors*: "cursor path navigation MUST be lattice-aware: at each level, the
  cursor consults the lattice type to determine the child lattice."
- CAD035 *Cursor Hierarchy* diagram and *Reference Implementation* table (four removed
  classes; "Lattice base + write-boundary hooks").
- CAD035 *Lattice-Aware Cursors*: the whole section describes cursors that carry an
  `ALattice`.
- CAD035 *Lattice Context*: "Lattice-aware cursors carry a `LatticeContext`" (all
  cursors do).
- CAD045 *Application Architecture*: "The lattice hierarchy MUST NOT have knowledge of
  cursors" now holds; the converse should be added.
- CAD045 *Custom Lattice Types* and *Zero Values and Container Types*: `zero()` "used
  by cursors for auto-initialisation", `path(key)` "enabling lattice-aware cursor
  navigation".
- CAD045 *Reference Implementation* tables.

Proposed wording: cursors know a lattice only through the shape, merge, signing,
ownership and stamping interfaces, each supplied by the component that owns the level.
The hierarchy becomes root (with the publication seam), path cursor (optionally
shaped), forked cursor, transaction draft and the update cursors.

## 4. Declared children replace lattice-walk auto-initialisation

**Status:** Implemented (`c1bfed931`).

`child(key, shape)` navigates one level while declaring the child's empty value, so a
write can create it and an update function receives the zero instead of null.
`ALatticeApplication.resolve(path)` walks the application root
lattice to declare a whole region path. `LatticeOps.assocIn` takes a shape: the base
is created from it, intermediates below it only for structural (JSON-like) shapes,
otherwise the write fails rather than guessing a container type.

CAD text affected:

- CAD035 *Path Cursor*: "with a lattice ... intermediates are created from `zero()`".
- CAD035 *Lattice-Aware Cursors*, auto-initialising writes paragraph.
- CAD045 *Wrapper Pattern*: "Each wrapper navigates one level deeper via
  `cursor.path(key)`. Signing, type management and merge semantics are handled
  transparently by the cursor chain."
- CAD045 *Writing Data*: "When a lattice is present, the update lambda receives the
  lattice's zero"; "All writes through cursors are lattice-aware by construction."
- CAD045 *Lattice Continuity*: the cursor consequence of a null child lattice.

Proposed wording: a wrapper declares its level's lattice when navigating to it; zero
substitution and typed creation follow from the declaration, not from a lattice found
by the cursor; an undeclared level is navigated untyped and cannot be created on
write unless its shape is structural.

## 5. Forks take their merge function at fork time

**Status:** Implemented (16 October 2026). Forks take their merge function at fork
time: `AForkableCursor.fork(merge)` returns a `ForkedCursor` whose `sync()` publishes
by compare-and-set and reconciles through the supplied function when the destination
has moved. The merge-less `fork()`, cursor-level `sync()` elsewhere and
`PublishingCursor` are gone; a component with a publication boundary owns such a
fork and supplies its lattice merge, under its context, as the function. The merge
function is two-way: the fork alone tracks the value it last reconciled against and
decides when a merge is needed. A composite application publishes each owned subtree
through that subtree's own fork of the host rather than reconciling subtrees inside
the root's merge, so no baseline is ever handed to a merge function.

Before this decision two forks existed. A component fork (`Social.fork()`,
`LatticeKV.fork()`, a `DLFSDrive` fork) was a new component whose private working
root published into the parent's cursor through a publication inner class on the
component base. A bare cursor fork, `AForkableCursor.fork()`, returned a working copy
whose `sync()` wrote the fork's snapshot over the parent unconditionally: a parent
that changed in between was overwritten, with no signal. Before Stage B the cursor
carried its lattice and `fork()` could merge; Stage B moved reconciliation to
components and left `fork()` with overwrite semantics only. The decision moves the
one real mechanism into the cursor layer, parameterised by a function the owner
supplies, and the component fork becomes a cursor fork the component owns.

Evidence at the time (16 October 2026, from a compiler probe over the whole tree): no
main code called the bare form; every `.fork()` in main was a component fork or the
unrelated CVM `Context.fork`. Three tests called it and none depended on overwriting
a changed parent. Decision 15 sharpened the question: every publisher must assume its
destination changed under it, and the bare fork was the one publish path that did
not. Chosen: the fork keeps its name and takes its merge function at fork time, so a
forgotten lattice cannot pass for a merge; the three tests moved to component working
state or a detached root. Adopting a value the destination view prepared, such as a
stamp, is now a property of the fork's own publish loop (`StampedCursorTest`).

CAD text affected:

- CAD024 *Cursors*: syncing "always succeeds without retry".
- CAD035 *Forkable Cursor*: `fork() → Cursor`; "`sync()` ... available on
  lattice-aware cursors".
- CAD045 *Fork and Sync*: "`cursor.fork()` creates an isolated working copy"; "Sync
  always succeeds when a lattice is present."

Proposed wording: a fork is created with the merge function of its position and
sync on such a fork always succeeds; a merge-less fork does not exist. The
fork-modify-sync pattern survives with the merge supplied at fork time rather than
carried by the cursor. CAD035 *Forkable Cursor* (initial value, compare-and-set
merge, lattice-aware sync) and CAD024 *Cursor Sync Guarantees* (`onSync`,
`ForkedLatticeCursor.sync()`) describe the removed cursor-lattice model.

## 6. Root publication seam needs a merge to absorb a racing write

**Status:** Superseded. Publication now belongs to components. `LatticeHost` has
no lattice: it persists one snapshot and retains concurrent application writes for
its next sync. It does not remerge the persisted snapshot over a newer publication.
The discussion below records the earlier cursor-based proposal.

`Root.onSync(publish, merge)` installs the host's publication callback and the merge
used when a write lands during publication. The earlier root component installed its
lattice. A root with a publication callback and no merge throws on that race, where
the old root fell back to its own lattice. Alternative: require the merge whenever a
callback is installed, so the race can never throw.

CAD text affected:

- CAD024 *Cursor Sync Guarantees*: `sync()` "fires the cursor's `onSync` callback";
  item 2 names `ForkedLatticeCursor`.
- CAD035 has no text on publication; the seam is an addition.

Proposed wording: publication is a host service installed at the root together with
the merge that reconciles a concurrent write; a fork's sync never reaches it; the
host calls the root's sync explicitly when merged state should be published.

## 7. Merge results are installed unstamped

**Status:** Implemented (`c1bfed931`).

`StampedCursor.merge(merge, value)` writes the merge result to its base as the merge
produced it. A last-write-wins winner keeps its own timestamp instead of being
re-stamped with the local clock, which would have let a merge invent a newer write.

CAD text affected: CAD035 *Write interception* table, `merge` column for the stamped
cursor ("select an existing value, no re-stamp" is the intent; make it a rule).

## 8. External key resolution belongs to the lattice, not the cursor

**Status:** Implemented (`c1bfed931`).

`ALattice.resolvePath(keys...)` canonicalises JSON-style keys level by level and is
the only resolution primitive. A component navigates with
`cursor.path(lattice.resolvePath(keys))`; the application's `resolve(path)` is the
typed walk over canonical keys. The cursor-level `resolve` and its associativity law
are gone (`ResolveTest` became `ResolvePathTest`).

CAD text affected: CAD035 *Lattice-Aware Cursors*, "Canonical vs logical keys —
`path` vs `resolve`".

## 9. Hosts merge path-addressed values with the served sub-lattice

**Status:** Implemented (`c1bfed931`); superseded 17 October, see entry 16.

`NodeServer` built a plain root and merged inbound path values with
`lattice.path(path)`; `mergeAt(value, path...)` was the public form. The node now
holds no lattice: a path delivery goes to each application's `mergeAt`, which walks
the path through the components that resolve, and the deepest merges the value reduced
to its path with its own behaviour.

CAD text affected: none found in CAD024/035/045; CAD036 (*Lattice Node*) should be
checked for how pull-path merges are described.

## 10. Transactions over a draft cursor

**Status:** Implemented (0.8.18 work, engine moved in `c1bfed931`).

`Transactions.transact` and `transactLocked` run a callback against a private draft
of a cursor's value and commit by compare-and-set, with locked fallback on a
`TransactionalRootCursor`. `TransactionalComponent` builds typed domain drafts on
it. Neither CAD035 nor CAD045 describes transactions; this is an addition to specify,
not a contradiction.

## 11. Owner slots are generically typed

**Status:** Decision needed.

`path(owner)` on an owner-keyed level returns a cursor typed by inference, so a
forgotten `SignedCursor.forOwner` is caught by the write guard at run time, not by
the compiler. A child typed as `SignedData` for owner levels (an `Ownership` cursor
method, or a shape-aware `child`) would make the omission a compile error. Affects
how strongly CAD045 *Signing Boundary* can state the guarantee.

## 12. Naming

**Status:** Decision needed.

`ALatticeComponent` holds a cursor that knows no lattice; `AComponent` was the name
used in discussion. Deferred to keep the Stage B diff reviewable. `RootComponent`
has since been renamed `LatticeHost` (`971790597`, see entry 14); the component base
class is still open.

## 13. In flight: application-private working state

**Status:** Implemented on the branch; described in LATTICE_APPLICATIONS under
*Batched signing*. Since 16 October 2026 a signed component publishes through its own
fork of the host, not into an enclosing application's working copy.

`ALatticeComponent` gains a constructor taking a lattice and a publication
destination, with private working state that publishes one snapshot into the
destination on sync, and `SignedComponent` keeps unsigned working state over a
signed destination, signing once at publication. The `beforeSync()` hook on
`ALatticeApplication` has since been demoted to an optional preparation step; the
examples publish their components in order from an ordinary `sync()`. This touches
CAD045 *Connecting to Node Infrastructure* and CAD024 *Cursor Sync Guarantees*
(which sync reaches which boundary).

## 14. The host carries no lattice; its store is optional

**Status:** Implemented (`971790597`). Store optional: agreed (15 October 2026).

`RootComponent` became `LatticeHost`. The host holds the published root value, an
optional store, the publication policy that runs on its sync, and an immutable list
of applications that receive incoming values. It has no lattice: it never merges on
its own, an unconfigured host ignores incoming values, and its sync is a plain
compare-and-set that leaves a racing write pending for the next sync (this is what
superseded entry 6). Without a store, persist returns its input and flush is a
no-op; a networked node still requires a store.

CAD text affected:

- CAD045 *Connecting to Node Infrastructure* and *Reference Implementation*: the root
  component holds the lattice and the store; a node is constructed from a lattice and
  a store.
- CAD036 (*Lattice Node*): how the node restores and merges, to check.

Proposed wording: the host is the authoritative local root: a published value, an
optional store and a publication policy. Merge semantics belong to the applications
and components it hosts; the host delegates every incoming value and merges nothing
itself.

## 15. Shared regions: components must be robust to external change

**Status:** Agreed (15 October 2026); documented in LATTICE_APPLICATIONS
(`f4e6a5798`, `6d486afdc`). On 16 October the rule that a staged keyed component
publishes only the keys it declares moved from the application class to the component
base: staging is uniform at every level and the root is not a special case. The
inferences below remain unconfirmed.

The shared-applications example stages each domain at its own path (a Social fork, a
DLFS region fork, a live P2P view) behind one live admission handler, and the
document now says whole-root staging is "not the composition mechanism" for several
applications. Decision: multiple applications may share regions. The requirement is
that they merge atomically on sync and allow for the destination having changed
under them. This is a general property of every component in the hierarchy: there is
usually one instance, but each must be robust to external change.

Inferences from that decision, not yet confirmed: whole-root staging by more than one
application is then permitted rather than discouraged; and a signer-free component
holding a signed slot reconciles by the lattice's own rule, which selects an
envelope, so the signer-free limitation is the lattice's semantics rather than a
prohibition on sharing.

CAD text affected:

- CAD045 *Fork and Sync*: "Sync always succeeds when a lattice is present" (still true
  for component forks, now stated for components rather than cursors).
- CAD045 *Application Architecture*: one application per region is not assumed.
- CAD024 *Cursor Sync Guarantees*: the guarantee moves to the component boundary.

Proposed wording: a component's sync reconciles its working snapshot with its
destination under its lattice and publishes the result; a component must assume its
destination can change between syncs; any number of components may publish into the
same region.

## 16. The node is replication only

**Status:** Implemented (`e166b6855` propagators, `c5c6a31a7` node). Decided
16 October 2026 that the principle covers propagation groups as well.

Decision: `NodeServer` replicates whatever it is given to its propagation sets.
Update and merge semantics belong to the host and the application/component tree,
including for propagation groups: a propagator announces the host's published root
through its publication filter and merges nothing.

Implemented as: `LatticePropagator` has no lattice, merge context, working view or
store root of its own, and attaching needs no lattice. `NodeServer` is constructed
from an assembled `LatticeHost` only; the lattice-taking constructors, the
restore-by-merge at launch and the merge-context accessors are gone.
`RelayHost.open(lattice, store, config, policy)` in convex-peer assembles the relay
case, one live application over the whole lattice, with restore, transactional root
and timestamp skew taken from node configuration. Restore adopts the retained root
at open, as `LatticeHost.open` does. Incoming completeness is unchanged: the protocol
endpoint acquires missing cells before the node's merge, and pulls acquire before
returning. The application-factory launch overload and the node's lattice accessor
went earlier (`971790597`).

On 17 October the P2P node followed (`3e08a8a35`). `P2PNode.create` opens the host
with `RelayHost.openHost`, configures `P2PApplication` on it as the receiver and
attaches replication; nothing below the root is the node's. The signing rules the
application used to re-apply along a path now live in the lattices (`246a20501`),
where an owner's slot verifies its signer for its owner through the context and the
lattice tree ends at a signed envelope. The node directory,
social selection and bootstrap paths are the application's, read from its lattice. The
node calls the application at the moments replication knows about, after the transport
binds, when a peer is admitted and when an inbound value is accepted, and turns what it
learns into connection intent, filters, pulls and route authentication. The application
syncs the host for edits its policy makes during admission, the one place it touches
publication, because the node's post-merge sync runs only when the merge itself changed
the root.

Later on 17 October the application merge took its intended shape. Mike's statement:
the lattice is a shape definition plus a default merge function; it is up to the
component implementation to delegate to it or not, and nothing assumes it as the
default. `ALatticeApplication.merge` is therefore abstract: every application states
how it merges an incoming root, `LatticeApplication` and `DLFSApplication` by
delegating whole, `P2PApplication` by delegating the root projected to the social
owners it wants and then recording their validated signers. A path delivery is every
component's business, through `mergeAt` on the component base: a component with a
child component for the first key (`child(key)`, none by default) hands the rest of
the path to it, and the deepest component that resolves merges the value reduced to
its path, siblings ignored, built from the empty containers its lattice declares,
through its own `merge`, whether that delegates to a lattice or does otherwise. Every
component has a `merge` (the base refuses, so a misrouted delivery fails rather than
vanishing), and a path the lattice does not declare delivers nothing, as an undeclared
key in a root does. Three cuts the same day converged here, one making `mergeAt` final
over a partial root, one merging with the lattice at the path, one resolving a whole
path to a component; Mike's rule each time was that the behaviour at a path is the
component's, and that the walk falls back to the last component that resolves. The
base helper `mergeDeclaredPath`, the path-ignoring default, the "declared path does
not imply interest" rule and the bootstrap after-merge steps went.

CAD text affected:

- CAD036 (*Lattice Node*): the node is constructed with a lattice and merges incoming
  values.
- CAD045 *Connecting to Node Infrastructure*.

Proposed wording: a node hosts an already-assembled host and attaches propagation
groups; it hands incoming values to the host and announces the host's published
root; it holds no lattice and performs no merge.

## 17. What "application" names

**Status:** Open.

Two things were called application: the admission handler configured on the host
(an `ALatticeApplication`: root lattice, incoming merge, typed navigation, optional
private working state) and a grouping of domain components forked at their own
paths. Since 16 October the groupings in the shared example extend the application
class too, so in code and manual "application" names one thing: the component at the
host root. What remains is whether the host-side configured list is called
applications or admission policy, and the architecture table row "root merge and
optional region factories".

CAD text affected: CAD045 *Application Architecture* layer table.

## 18. Named regions compose application behaviour

**Status:** Implemented, 18 October 2026.

Mike proposed an `AKeyedRegions` component that declares the regions an application
is interested in and the components handling them, including at the host root.
Applications should reuse standard merges rather than reconstruct container merges
themselves. Explicitly supplied components may be shared by applications.

`AKeyedRegions` extends the component base and registers children by keyword.
`createRegion` constructs a child at a supplied region cursor with the resolved
context; `region` retains an existing child without rebinding it or changing policy.
Both root and path deliveries use the child's delivery entry point. Missing and
unregistered entries do nothing. Registration freezes at first use.

The container has no private whole-root snapshot. Sync publishes its children at
their own boundaries, leaving all other paths alone. Staged state is read through
the children; the container cursor shows the destination. Merge and publication
across children are sequential, not an atomic aggregate. A shared instance shares
pending state as well as its publication destination; repeated lattice deliveries
are idempotent. `LatticeComponent` supplies the standard lattice-backed child.

`SingleRegion<C>` and `Regions` provide concrete implementations for one typed
child and a configurable collection respectively. The local application testbed
uses these directly: favourites can share a region instance, the notebook retains
its deferred signing boundary, and the task board retains one aggregate boundary
for transactions across its sets. These applications need no custom root merge or
sync implementation.

The host accepts any root component, so a normal application can inherit
`AKeyedRegions` without also inheriting `ALatticeApplication`. The latter remains a
convenience for root lattice navigation and custom application merges. This is a
component composition choice, not a change to lattice merge algorithms or encoding.

CAD text affected: CAD045 *Application Architecture*, *Wrapper Pattern* and
*Connecting to Node Infrastructure*. The earlier cursor/component discrepancies
remain; this entry records the agreed component-owned composition.

## 19. Owned-map components bind authorisation and publication

Implemented 18 October 2026. `AOwnedMap` extends the concrete `LatticeComponent`
with standard owner views and drafts. `OwnedMap` and `OwnedIndex` require an
explicit verifier; `KeyOwnedIndex` specialises the index to public-key equality.
The component binds this rule to an existing owner lattice, so full-map and
owner-path delivery share verification. Views sign into the map's working state;
independent drafts sign on their own sync. Map sync never implicitly publishes a
draft. Components do not retain or cache domain views or drafts.

Changed foreign entries are checked for a signed envelope before typed dispatch.
`SignedLattice` checks its payload lattice's foreign-value validator before
acceptance or signing. Invalid entries leave their owner slot unchanged and do not
prevent unrelated valid entries merging. A malformed containing value can still
abort the atomic merge. Value lattices remain responsible for domain validation.

CAD038 *Owner Verifier* describes context-based verification; these components use
the instance-bound verifier already introduced by the typed owner lattices. Its
fail-closed guidance and legacy permissive fallback conflict for indirect owners:
the new components require an explicit verifier, as agreed, while the legacy
`OwnerLattice` authorisation fallback remains unchanged. CAD043 governs identity
resolution and CAD004 distinguishes account identity, keys and controllers; this
change supplies no new DID method or account-authorisation rules. Applications
provide local resolver data, and a changed resolver governs future verification
without deleting previously accepted values.

CAD045's component/cursor differences recorded above also apply to these staged
owned components. Favourites and the DID-keyed scores example exercise the new
component API in the local application testbed.

## 20. Signed domain components are constructed directly at paths

Implemented 18 October 2026. `ASignedComponent` owns a live signing boundary over
an explicitly supplied signed storage cursor and owner-bound rule. Domain classes
extend it and expose operations over its unsigned cursor. Incoming envelope merges
use the same rule; signing uses the component's current context. Multiple instances
at a path share edits immediately. Sync does not publish their containing map.
`SignedComponent` remains the independent draft which signs only on sync.

Removed the owned-map view factory and then its replacement signing-view helper.
Applications construct their domain classes directly. `AOwnedMap.path(owner)`
supplies a signed slot and constructs a missing map only on the first changed write;
plain cursor paths suffice when the containing map exists. Favourites and scores
exercise this pattern. The favourites root also constructs its region directly at
`host.cursor().path(FAVOURITES)`. CAD045 describes the resulting component pattern;
CAD038's authorisation requirements remain unchanged.

## 21. Owned collections merge with public information only

Implemented 18 October 2026. `AOwnedMap` now extends `ALatticeComponent` directly
and uses the context-free owner lattice for both incoming merges and publication
conflicts. `OwnedMap`, `OwnedIndex` and `KeyOwnedIndex` constructors no longer take
a context. They preserve existing signed envelopes and never resolve authoring
capabilities. If payload merging synthesises a third value, the collection keeps
its established envelope until an authorised component authors a result.

`SignedLattice.merge(own, other)` uses context-free payload merging and never
signs. Its explicit-context overload remains available to signed components that
define an authoring policy. `ASignedComponent` can reconcile and re-sign under
that policy; drafts require an explicit signing context. The favourites example
defines its key strategy once, in `Favourites`: use the supplied user's key, with
no inherited host or region fallback. CAD038 and CAD045 clarify this boundary.

## 22. Signed components take an explicit signer function

Implemented 18 October 2026. `ASignedComponent`, `SignedComponent` and the owned
map's `draft(owner, signer)` helper take `Function<V, SignedData<V>>`. Domain
constructors pass `user::signData`; a dynamic function may resolve a wallet key
when called. The same function handles local edits and re-signing. Components no
longer construct or inherit a signing context, and the generic component base no
longer contains a signing-cursor helper. DLFS and Social construct their existing
signing boundaries locally, retaining their legacy context policy.

The bound `SignedLattice` checks the returned payload, signature and owner
authorisation. `sign(value, signer)` checks local authoring;
`mergeAndSign(own, other, signer)` explicitly permits synthesis. Plain
`merge(own, other)` remains public and never signs. Existing context overloads
remain for legacy lattice callers. Null or unauthorised results leave synthesis
unchanged and fail local publication; substituted payloads and invalid signatures
throw before storage. Unchanged edits and selected envelopes do not invoke the
signer. Failed draft publication retains the pending edit for retry.

Favourites defines its key strategy once as `user::signData`. Its owner region
still has no signing capability. CAD038 and CAD045 document the checked signer
result and explicit capability boundary.

## 23. Timestamped components own stamping and ordering together

Implemented 18 October 2026. `Timestamped` is a live component wrapper whose
editing cursor is a `StampedCursor`. Incoming merges use the underlying storage
and whole-record ordering, without re-stamping. The standard map factory handles
`:timestamp` and shares one validating lattice with enclosing signed and staged
components. Custom record representations can provide their own ordering and
stamping rule. The wrapper creates no fork and sync does not publish its storage.

Favourites embeds `Timestamped` around its unsigned record, then constructs its
set component at `record.cursor().path(ITEMS)`. Its owner index uses
`Timestamped.lattice()`, removing the application's timestamp extractor and
stamping lambda. Its public editing cursor also follows this path. Signed
incoming envelopes still enter at the enclosing signed boundary, retaining the
selected envelope. CAD024's whole-record LWW rule and CAD035's cursor mechanics
are unchanged; CAD045 describes the standard component composition.

## 24. Abstract lattices are optional component implementations

Implemented 20 October 2026. `ALatticeComponent` no longer accepts or exposes
`ALattice`. It supplies storage, child routing and a `mergePublication(own, other)`
hook. Unsupported descendant paths are rejected by `mergeAtPath` unless a
component defines their handling. A staged component can reconcile directly over
immutable values without constructing a lattice definition.

`LatticeComponent` is the explicit adapter for lattice-based implementations. It
owns lattice path reduction, optional shape-based cursor construction and keyed
publication reconciliation for simple values and caller-supplied lattices.
`LatticeApplication` opts into these operations (see entry 25). Domain components extend
`ALatticeComponent` directly and call lattice rules in explicit publication or
domain operations; using a lattice internally does not grant generic incoming
merge or path routing. Owned maps explicitly accept complete owner envelopes and
stop delivery at that boundary. Known map and index paths use explicit
cursor defaults rather than constructing lattice trees merely for navigation.

This follows CAD045's distinction between component behaviour and reusable value
semantics, and CAD035's policy-free storage and fork reconciliation. The standard
merge and signature rules are unchanged.

## 25. Applications own lifecycle independently of lattice definitions

Implemented 20 October 2026. `ALatticeApplication` extends `ALatticeComponent`
without accepting an `ALattice`. It retains application preparation and adds the
managed-child publication stage: `beforeSync()`, `syncChildren()`, then its own
boundary. Failure prevents later stages but does not undo successful children.
`AKeyedRegions` extends this application base, freezes registrations before
preparation and publishes children in registration order. Nested applications
participate through the same lifecycle. The empty collection remains inert.

`LatticeApplication` is the explicit, extensible adapter for a supplied root lattice.
The P2P and DLFS APIs that accept such lattices opt into it. Generic lattice path
reduction and publication reconciliation are shared with `LatticeComponent` through
`LatticeOps`; neither operation is required by ordinary applications. Standard
region entry points navigate their known index/map types without a root lattice.
This preserves CAD045's component boundaries and CAD035's fork semantics.

## 26. Default sync follows explicit publication ownership

Implemented 21 October 2026. `ALatticeComponent` no longer overloads live and
staged construction on `AForkableCursor` versus `ACursor`. `super(cursor)` shares
storage; `super(forkOf(destination))` explicitly creates an owned fork. Component
copying and stored-representation conversion retain their explicit fork semantics.

Default sync publishes only the owned fork, or reads the current value when there
is none. It never publishes or refreshes a caller-owned fork through a view, nor
signs, stamps or creates default state. Nested publication stops at its immediate
destination. Application preparation and managed-child sync remain explicit
lifecycle extensions. Regression tests cover these ownership boundaries, cursor
declaration independence and no-op identity through authoring views.

## CAD-by-CAD summary

| CAD | Sections | Entries |
|---|---|---|
| CAD024 Data Lattice | *Cursors*, *Signed Lattice*, *Cursor Sync Guarantees*, implementation note on cursor types | 1, 3, 5, 6, 13, 15 |
| CAD035 Lattice Cursors | *Path Cursor*, *Forkable Cursor*, *Cursor Hierarchy*, *Lattice-Aware Cursors*, *Lattice Context*, *Reference Implementation*, Java example | 2, 3, 4, 5, 6, 7, 8, 10 |
| CAD045 Lattice Applications | *Application Architecture*, *Custom Lattice Types*, *Zero Values*, *Lattice Continuity*, *Signing Boundary*, *Local Trust vs Network Verification*, *Wrapper Pattern*, *Writing Data*, *Fork and Sync*, *Connecting to Node Infrastructure*, *Reference Implementation*, *Cursor Chain* | 1, 2, 3, 4, 5, 10, 11, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24 |
| CAD036 Lattice Node | pull-path merge description, node construction and restore | 9, 14, 16 |
| CAD038 Lattice Authentication | *Owner Verifier*, indirect-owner default policy, explicit authoring | 19, 20, 21, 22 |
