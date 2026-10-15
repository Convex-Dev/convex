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

The entries below record earlier design steps and may name APIs superseded by the
current branch. See [LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md) and
[LATTICE_APPLICATIONS.md](LATTICE_APPLICATIONS.md) for the current API:

- Cursors carry no lattice, context or container-construction policy. Components
  supply read/write functions and explicit signing/stamping functions.
- Ordinary roots hold only current atomic state. `PublishingCursor` wraps host
  storage with serialised publication; the old `Root.onSync` and `Root.view`
  methods are removed.
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

**Status:** Decision needed.

`fork(merge)` records the merge; `sync()` converges with the parent however it has
moved, with the fork's edits in the own position. `fork()` with no argument still
exists and means write-back: a parent that changed in between is overwritten. Every
component passes its lattice, and the javadoc states the hazard.

Evidence for removing the bare form: `LatticeKVTest.testConcurrentCounterMerge`
forked two replicas with the bare call and the second sync silently overwrote the
first, caught only because the test counted. A forgotten argument is the most likely
way a future component loses data. Alternatives: remove `fork()` and require an
explicit null or a named write-back constant, or keep it for lattice-free scratch
roots.

CAD text affected:

- CAD024 *Cursors*: syncing "always succeeds without retry".
- CAD035 *Forkable Cursor*: `fork() → Cursor`; "`sync()` ... available on
  lattice-aware cursors".
- CAD045 *Fork and Sync*: "`cursor.fork()` creates an isolated working copy"; "Sync
  always succeeds when a lattice is present."

Proposed wording: a fork is created with the merge function of its position; sync on
such a fork always succeeds. Whether a merge-less fork exists depends on the decision.

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

**Status:** Implemented (`c1bfed931`).

`NodeServer` builds a plain root and merges inbound path values with
`lattice.path(path)`; `mergeAt(value, path...)` is the public form. A path outside
the served lattice is rejected before any navigation. `LatticePropagator` keeps a
plain root for its working view.

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
*Batched signing and application preparation*.

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

**Status:** Agreed in principle (15 October 2026); document wording pending.

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

**Status:** Agreed in principle (15 October 2026); implementation to review.

Decision: `NodeServer` replicates whatever it is given to its propagation sets.
Update and merge semantics belong to the host and the application/component tree.
Three things on the branch still put merge semantics in the node layer:

- The lattice-taking node constructor creates a host, installs a standard live
  `LatticeApplication` as a relay, and restores the retained root at launch by
  merging it through that application. This gives a second restore semantics beside
  `LatticeHost.open`, which adopts the retained value.
- The node carries a merge context which it pushes into the host and the relay
  application.
- A propagator constructed with a lattice keeps its own working view and pre-merges
  it with node snapshots before announcing, with tie precedence for the node's
  published state. A lattice-free propagator constructor also exists.

Open: whether "replicate whatever it is given" covers only `NodeServer`, or also the
propagator's working view and pre-merge. If the former, the lattice-taking node
constructor and the node's merge context are the remaining items; if the latter,
propagators would announce the host's published root through their filter with no
merge of their own, and restoration becomes host assembly. The application-factory
launch overload and the node's lattice accessor are already gone (`971790597`).

CAD text affected:

- CAD036 (*Lattice Node*): the node is constructed with a lattice and merges incoming
  values.
- CAD045 *Connecting to Node Infrastructure*.

Proposed wording: a node hosts an already-assembled host and attaches propagation
groups; it hands incoming values to the host and announces the host's published
root; it holds no lattice and performs no merge.

## 17. What "application" names

**Status:** Open.

Two things are now called application: the admission handler configured on the host
(an `ALatticeApplication`: root lattice, incoming merge, typed navigation, optional
private working state) and a grouping of domain components forked at their own
paths, which in the shared example extends nothing. Entry 15 removes the pressure to
choose between them by allowing shared regions, so what remains is naming and the
architecture table row "root merge and optional region factories". Options: the
application is the admission handler and domain groupings are assembly code; or keep
the application base class as the developer-facing concept and call the host-side
list the admission policy.

CAD text affected: CAD045 *Application Architecture* layer table.

## Not affected

CAD038 (*Lattice Authorisation*): verification at the merge boundary is unchanged.
The owner lattices still verify signer against owner on every context-aware merge.

## CAD-by-CAD summary

| CAD | Sections | Entries |
|---|---|---|
| CAD024 Data Lattice | *Cursors*, *Signed Lattice*, *Cursor Sync Guarantees*, implementation note on cursor types | 1, 3, 5, 6, 13, 15 |
| CAD035 Lattice Cursors | *Path Cursor*, *Forkable Cursor*, *Cursor Hierarchy*, *Lattice-Aware Cursors*, *Lattice Context*, *Reference Implementation*, Java example | 2, 3, 4, 5, 6, 7, 8, 10 |
| CAD045 Lattice Applications | *Application Architecture*, *Custom Lattice Types*, *Zero Values*, *Lattice Continuity*, *Signing Boundary*, *Local Trust vs Network Verification*, *Wrapper Pattern*, *Writing Data*, *Fork and Sync*, *Connecting to Node Infrastructure*, *Reference Implementation*, *Cursor Chain* | 1, 2, 3, 4, 5, 10, 11, 13, 14, 15, 16, 17 |
| CAD036 Lattice Node | pull-path merge description, node construction and restore | 9, 14, 16 |
