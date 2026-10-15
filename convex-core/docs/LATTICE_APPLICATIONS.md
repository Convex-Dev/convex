# Lattice Application Patterns

How to build an application on the Convex Data Lattice: design a lattice for the data,
wrap it in typed components over cursors, and connect it to a local store or a hosting
node without the application knowing which. The normative reference is
[CAD045](https://docs.convex.world/docs/cad/lattice_apps); this document is the
practical companion for `convex-core`, with `convex-social`, `convex-dlfs` and
`convex-p2p` as worked examples. Cursor internals are in
[LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md).

**Branch prototype:** component-owned working state and publication below are an
intentional revision of CAD035/CAD045. Those CADs still describe lattice-aware
cursors and automatic cursor signing boundaries; they need corresponding updates
before this design becomes normative. Ownership remains governed by CAD038.

## Application root and components

**Implemented:** `ALatticeApplication<V>` has working state for a hosted root and
declares the root lattice: it exposes `getLattice()`, atomically merges incoming root
values through `merge(value)` and hands out typed region cursors through
`resolve(path)`. Cursors themselves know nothing of lattices; components declare
the structure of each level they own.
It attaches to a `RootComponent<V>`, which explicitly creates or opens a local
root, or hosts a networked root. Neither merging nor hosting requires domain
components to exist.

The global lattice has a keyed map root. The existing `KeyedLattice` represents it
as `Index<Keyword, ACell>`, an `AMap`. Applications participate at the globally
defined region paths and meanings, composing the region lattices they understand.
Unknown incoming regions are ignored by keyed merge; existing unknown regions are
retained. This is selective participation, not a separate application namespace.
The generic base also supports other value types for local applications and tests.

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
public final class RelayApplication extends ALatticeApplication<Index<Keyword, ACell>> {
    public RelayApplication(RootComponent<Index<Keyword, ACell>> host) {
        super(host);           // private working state, using the host's lattice
    }
}

var host = RootComponent.open(selectedRegions, store);
RelayApplication app = new RelayApplication(host);
app.merge(incomingRoot); // atomic local merge, including region verification
app.sync();              // merge working state back to the host
host.sync();             // persist and publish through host policy
```

`RootComponent.open(lattice, store)` restores the retained root, or starts at the
lattice's zero value if the store has none. Use `RootComponent.create(lattice, store)`
to deliberately start a fresh root; its first publication replaces any retained root.
Applications accept a host rather than a store, so this choice belongs to host assembly.
The caller owns the store lifecycle. An application defaults to the host's lattice,
or supplies its own with `super(applicationLattice, host)`. Applications may also
override `merge` to delegate to components with their own merge strategies and
discard input they do not accept. Verification must still use the applicable
owner-bound rules for the paths they publish.
`merge` uses the cursor's effective context, preserves the current root in the
`own` position, and retries after concurrent updates. It does not publish or flush.

### Incoming delegation

After constructing applications against a restored host, configure delegation once:

```java
root.configureApplications(List.of(social, files));
root.merge(incomingRoot); // delegates; no additional host merge
social.sync();           // publishes accepted application state into root
files.sync();
root.sync();             // persists and propagates the published root
```

The root retains an immutable application list. It delivers incoming values to
each application's `merge` and retains only their published results. An application
can route signed subtrees to `SignedComponent.merge`, which verifies the envelope
before atomically merging its payload into unsigned working state. It need not
call the base application merge afterwards: discarded input stays discarded.
Verify enclosing signed envelopes before routing values extracted from them.

Delivery is not a transaction across applications. If a later application fails,
earlier merges remain; callers may retry lattice delivery. The returned root may
still be unchanged until application sync. An explicitly empty list ignores all
incoming state. For compatibility, an unconfigured root has a generic live relay
application that preserves immediate root merging.

If an application publishes during configured host persistence, the newer
application result remains pending in the root. The host does not re-merge the
older persisted snapshot over it using its own lattice.

This is currently a local component API. Routing all NodeServer ingress through
it and constructing applications after node restoration are the next integration
step; node transport and launch behaviour have not been changed in this prototype.

### Sharing a host

The base host constructor creates private working state for each application,
initially sharing the host's immutable value. It allocates a cursor, publication
baseline and destination link, without copying the tree or starting threads:

```java
RelayApplication first = new RelayApplication(host);
RelayApplication second = new RelayApplication(host);
first.merge(incomingRoot);
first.sync();   // merge into host; second still has its previous working value
second.sync();  // reconcile second's work with the host
host.sync();    // host persistence and propagation
```

Lightweight children share paths in their enclosing working cursor. They need no
fork for ordinary atomic updates. Context defaults are resolved when an independent
working boundary is constructed; subsequent overrides on that cursor reach its
live descendants, without changing the host or other independent boundaries.

Publication captures one working snapshot. If the destination still equals the
last publication baseline, it installs the snapshot directly. Otherwise it merges
with the destination using the component's lattice and context, then retries CAS.
An unchanged working snapshot adopts the destination. Equality, rather than object
identity, determines whether a merge or preparation must be repeated. Publications
at one boundary are serialised; ordinary working edits continue and remain pending
if they land after the captured snapshot. A successful publication stops at its
destination; it does not recursively persist or publish the host.

`mergePublication(lattice, baseline, own, other)` is the component's overridable
CAS-conflict merge. It receives immutable snapshots and must not modify live
components. Local edits are **own**, so an intentional equal-timestamp LWW edit
replaces its predecessor. An unchanged branch adopts the destination instead.
Applications with independently published children apply those rules at the
appropriate child boundaries, rather than treating an old whole-root snapshot as
an edit to every branch. No generic field-wise merge is imposed on custom lattices.

For a signed child, call `child.mergePublication(baseEnvelope, ownEnvelope,
otherEnvelope)`. It uses that child's lattice and signing context, reuses an
existing envelope when possible and signs a combined payload when necessary.
Missing signing authority fails the attempt instead of silently discarding one
branch. Two applications may independently author the same subtree: each merges
against the latest published state before its CAS can succeed.

On a retry, the previous prepared candidate becomes own and the previously observed
destination becomes baseline. This avoids signing again when only an unrelated
branch changed. The same snapshot merge reconciles concurrent local edits back
into working state after publication; such edits remain pending for the next sync.
Preparation, including signing, always occurs outside retryable atomic callbacks.
Only sync is specialised on the private working root; ordinary updates retain
their usual atomic implementation and acquire no publication lock.

The explicit `ALatticeApplication(lattice, cursor)` constructor retains the supplied
cursor's policy. Pass `host.cursor().view()` for immediate shared visibility, or a
transaction draft when rebinding. Existing `P2PApplication` and `DLFSApplication`
adapters retain this live policy. In particular, a NodeServer adapter is constructed
before node launch restores the retained root; taking its working snapshot at that
point would be premature. Construct independent working applications after host
restoration. Host merges never borrow application signers.

These policies are not an access-control boundary between code sharing a process.
Verification remains bound to the lattice instances, configured before use.

### Batched signing and application preparation

`SignedComponent<V>` owns an unsigned working cursor and a raw `SignedData<V>`
destination. Bind it to the `SignedLattice<V>` at that exact host path, retaining
its owner verifier. Domain components can use its working cursor through their
`withCursor` factories. They update normally, without signing each edit:

```java
var signed = new SignedComponent<>(owners.signingFor(owner), signedSlot);
signed.cursor().setContext(ownerContext);
var user = existingUser.withCursor(signed.cursor());
user.feed().post("First post");
user.feed().post("Second post");
signed.sync(); // one signed publication to signedSlot
```

On sync, the component merges the unsigned payload, signs the prepared candidate
outside the destination CAS, and publishes the envelope. An unrelated ancestor
write reuses that signature; a conflict changing the payload requires preparation
again. Unchanged payloads do not sign. Signing failure before installation leaves
the destination unchanged. If subsequent working-state reconciliation fails, the
completed destination publication remains; sync is not a rollback transaction.
Snapshot reconciliation accepts values already admitted by the application;
untrusted envelopes must enter through verified merge first.

Applications choose their owned components and checkpoint order explicitly:

```java
@Override protected void beforeSync() {
    socialOwner.sync();
    nodeOwner.sync();
}
```

`app.sync()` calls `beforeSync()` before publishing the application snapshot.
There is no component registry, parent-component link, scheduler or hidden worker.
If preparation throws, that call does not publish the application. Earlier child
checkpoints remain in working state for retry. Hooks may run concurrently and must
use thread-safe component operations. This is not an atomic transaction across
independently editable child roots. Direct cursor or child sync does not execute
the application lifecycle hook; use `app.sync()` to run the complete preparation.

An owner boundary reconciles the value visible at its immediate destination.
When enclosing application publication encounters another signed version at the
shared root, its `mergePublication` delegates that branch to the owner component.
The owner can prepare the combined envelope without advancing its live cursor.
The root needs no signing capability or secondary acceptance policy.

## Proposed lifecycle and subscriptions

**Design proposal.** The root merge API and ownership hierarchy are implemented;
lifecycle and subscription signatures below remain proposals. The sections after
this proposal describe the existing component API. This design uses CAD024 for merge semantics, CAD035
for cursor atomicity, CAD036 for hosting, CAD038 for ownership and CAD045 for
application composition. Changes to their application contracts need corresponding
CAD updates before implementation is described as normative.

### Responsibilities

An application remains a typed component at the complete root. Its supplied
lattice is sufficient for root merging; domain subclasses add component factories.
Lifecycle and subscriptions manage active work and replication demand without
becoming another source of merge semantics.

| Object | Responsibility |
|---|---|
| `RootComponent<V>` | Shared authoritative cursor, schema, storage and host services |
| `ALatticeApplication<V>` | Root merge API; optionally domain factories, lifecycle and subscriptions |
| `LatticeSubscription<V>` | Interest in one subtree, a live cursor and optional change observers |
| `ALatticeComponent<V>` | Lightweight domain API around a cursor at one path |

Keep `RootComponent` as the host abstraction initially. A local root supports the
same application API as a root hosted by `NodeServer`; the node installs replication
services. A new host interface is only needed if another implementation requires it.
`ALatticeApplication<V>` continues to extend `ALatticeComponent<V>`.
Application-selected region definitions configure the host root at construction;
the host does not need to know the corresponding component classes. Combining
different applications' region selections in a shared host remains future work.

Signing establishes responsibility for the entire signed subtree. Its owner is
responsible for merging and publishing that tree, including coordinating multiple
writers using the same signing identity. Preserve the existing timestamp and
own-value tie rules. The API does not introduce a hidden logical clock or require
non-owner peers to synthesise signed values. Timestamped replicated registers should
be enclosed by an appropriate signing boundary.

### Owner identity policies

**Implemented:** `AOwnerLattice`, `OwnerMapLattice`, `OwnerIndexLattice` and
`OwnerKeyLattice`. The existing `OwnerLattice<V>` remains a compatibility API.

**Design decision:** verification rules belong to lattice instances. Different
paths can compose different rules, including different rules for the same owner
identity. Navigating to a child or deriving a restricted interest view preserves
the applicable verification boundaries; neither operation substitutes a global rule.

An owner need not be a public key. A region can map DID strings to `SignedData<V>`
and bind the rule that authorises a signing key for each DID to its owner lattice.
Share ownership machinery in `AOwnerLattice`, with concrete types selecting the
container and key conventions. Both hash-map and index variants support custom
owner verification. The index variant uses the existing `Index` value type; it
does not require a new CVM collection type.

```java
// K is the owner identity type; V is the unsigned payload type.
public abstract class AOwnerLattice<
    K extends ACell,
    V extends ACell,
    M extends AMap<K, SignedData<V>>>
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

// The same ownership policy with an Index-backed DID namespace.
OwnerIndexLattice<AString, MyState> indexedIdentities = OwnerIndexLattice.create(
    stateLattice,
    (owner, signingKey) -> didAuthority.isAuthorised(owner, signingKey));

// Direct public-key ownership with an Index-backed container.
OwnerKeyLattice<MyState> keys = OwnerKeyLattice.create(stateLattice);
```

`AOwnerLattice` shares the keyed merge algorithm, bound verification policy,
owner-bound signed-child construction and enforcement across merge/write entry
points. Concrete implementations supply the correctly typed empty container,
container validation and key validation/resolution. Both `AHashMap`
and `Index` already support keyed `mergeDifferences`, so each keeps its structural
merge implementation. The base must not rebuild either collection by enumerating
all entries. Container validation remains enforced in merge, not only in an optional
`checkForeign` pre-check.

`OwnerKeyLattice` fixes authorisation to owner-key equality with the signing key;
there is no custom predicate needed for that case. It validates the 32-byte key
representation and resolves supported blob/hex inputs to `AccountKey` at typed
boundaries. Generic parameters alone cannot validate decoded foreign keys.
An index decoded from the wire can retain plain `Blob` keys even when its Java
type is `Index<AccountKey, ...>`. Extract such keys as `ACell` and coerce them with
`OwnerKeyLattice.resolveKey(key)` (which returns `AccountKey`) rather than casting.
Merge does not rebuild the index solely to change these equivalent Java objects.
`OwnerMapLattice` and `OwnerIndexLattice` both accept custom predicates without
requiring a subclass for each DID method. Container choice is independent of
ownership policy. `OwnerIndexLattice` supports any key type admitted by `Index`
(`K extends ABlobLike<?>`), which includes `AString` DID keys as well as account
keys, blobs and keywords. It does not broaden `Index` to arbitrary `ACell` keys;
those remain supported by the hash-map variant. Key validation and resolution must
respect the selected owner's representation, including the index's blob-based key
equality, rather than assuming every indexed owner is a public key.

The predicate has type `BiPredicate<K, AccountKey>` (the factory can accept
`BiPredicate<? super K, ? super AccountKey>`). It answers whether a key
may author this owner's subtree, allowing multiple authorised keys without reducing
every identity to one public key. DID resolution is an implementation detail of the
supplied authority service. `SignedLattice` still verifies the cryptographic
signature separately. The lattice supplies the `SignedData` wrapper: callers specify
`OwnerMapLattice<AString, MyState>`, not `OwnerMapLattice<AString, SignedData<MyState>>`.
New public-key namespaces can use `OwnerKeyLattice<V>`; hash-map-backed public-key
namespaces can use `OwnerMapLattice<AccountKey, V>` with a direct-key predicate.
Mixed hash-map owner namespaces can explicitly use `ACell` as their key type.
`Index` and `AHashMap` are different stored
representations: introducing the specialised lattice must not silently change an
existing region's representation. Java cannot support both one- and two-parameter
classes named `OwnerLattice`, so the typed hash-map variant is named
`OwnerMapLattice<K,V>`. Existing `OwnerLattice<V>` factories, subclasses and stored
representations remain supported.

The bound predicate is authoritative for owner-to-signer matching in this region;
an application cursor context cannot replace it. Nested ownership boundaries each
retain their own rules; a child rule does not waive an enclosing rule.
The context continues to provide the available signing capability and write clock.
Host admission policy may independently decline to adopt data. A predicate rejection
or unresolved identity cannot fall back to accepting an arbitrary signer.

The same ownership rule must apply to both merge overloads, merges directly at an
owner's signed child, and local authoring below the signing boundary. Binding it
only to the outer map merge would leave path-based entry points inconsistent.
The owner-bound `SignedLattice` and its write cursor must retain the rule. Authoring
still requires an available authorised signer; the predicate grants no signing
capability and need not select a private key.

For stateful DID methods, resolve and refresh authenticated authority information
outside atomic update retry loops. Verification uses a consistent local authority
view for one operation; unknown resolution must not cause unbounded network work in
merge. CAD038 currently describes verification against current authority state.
Changing that view governs subsequent incoming claims; it does not automatically
erase previously accepted data or define historical-signature validity.

The compatibility `OwnerLattice<V>` still uses `LatticeContext.withOwnerVerifier`
on context-aware merge, including its lenient fallback for indirect owners; its
context-free merge retains signature-only checking. The new typed variants require
an explicit bound predicate, except `OwnerKeyLattice`, which supplies direct-key
equality. They do not consult the context verifier. Their signed boundary checks
authoring with the same bound predicate used by incoming merge; legacy `signAs`
behaviour remains available to existing callers.

Migrating a hash-map region to `OwnerMapLattice` can preserve its data representation
while explicitly binding its verifier. Applications are not migrated automatically.
The long-term design retires context-wide owner verification after these callers
migrate. CAD038 needs to describe the new instance-bound policy option and reconcile
its safe-default wording with its documented legacy lenient fallback.
DID method and account authority details follow CAD043 and CAD004.

### API sketch

These signatures show the proposed public surface; they are not compilable examples
against the current implementation. Paths use canonical CVM keys, as existing
`cursor.path(...)` calls do.

```java
public abstract class ALatticeApplication<V extends ACell>
        extends ALatticeComponent<V> implements AutoCloseable {
    protected ALatticeApplication(RootComponent<V> host);

    public final ALattice<V> getLattice();
    public V merge(V other);

    public final void launch();
    public final void close();

    public final <T extends ACell> LatticeSubscription<T> subscribe(ACell... path);
    public final void sync();
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

The generic subscription factory follows the existing cursor API: the schema and
domain factory establish the value type, not inference from a raw path. Domain
factories should hide this unchecked boundary from ordinary callers. Unknown schema
paths fail at subscription creation; an absent value at a known path is valid.
The default `writeContext` inherits host policy; an override supplies the
application's write policy before binding cursors. The default `onError` reports
asynchronous failures through logging; applications can override it for supervision.

```java
public final class MyApplication extends ALatticeApplication<Index<Keyword, ACell>> {
    private Feed feed;

    public MyApplication(RootComponent<Index<Keyword, ACell>> host) {
        super(host);
    }

    @Override
    protected void onLaunch() {
        LatticeSubscription<Index<Blob, ACell>> posts = subscribe(FEED_PATH);
        feed = Feed.connect(posts.cursor());
        posts.watch(this::refreshTimeline);
    }
}

MyApplication app = new MyApplication(node.getRootComponent());
app.launch();  // node already launched and configured
// The same application can launch against a standalone RootComponent.
```

`Feed.connect` above is a proposed cursor-based component factory. A lightweight
component holds a working cursor, with no component parent or host link. The one
component family that needs a store before publication, DLFS, is handed the host
store explicitly by its application. Component creation opens no connections,
starts no workers and installs no observers.

### Launch and close

- Host schema and peer groups are configured before host launch. Application launch
  uses its bound, ready host, binds its policy and runs `onLaunch`. Readiness means
  locally usable; it does not mean every remote peer has been contacted.
- Application launch is single-use. If `onLaunch` fails, release registrations made
  during launch and leave the host and other applications running.
- `subscribe` is valid during `onLaunch` and while running. It establishes a local
  view immediately and requests replication through the host's configured services.
  It does not wait for network acquisition. Local-only hosts create the same view
  without network activity.
- Application policy supplies signing and timestamp capabilities through the
  relevant working cursors, before writes begin.
  It must not mutate the shared host cursor's context. Merge authorisation is bound
  to the host's lattice instances; an application's signing capability does not
  replace it.
- `close` is idempotent. It stops accepting subscriptions, invokes `onClose`, and
  releases all application registrations even if cleanup fails. It does not close
  the shared host, store or peer groups. Subscriptions can also close individually.
- Closing a subscription releases its interest and observers; it does not erase
  state or revoke already-created cursor views. Components remain ordinary views,
  and their owner is responsible for ceasing work before application shutdown.

### Interests as restricted lattice views

A keyed lattice is a **typed interest set**: the declared keys select the children
in which it participates, and each child defines how that selected data merges.
`KeyedLattice` expresses a finite set; `MapLattice` and `IndexLattice` express a
family of keys sharing child semantics. Compose these for structured records instead
of implementing a custom merge for each record.

Runtime subscriptions select from the host's existing lattice tree:

- An empty path selects the whole configured lattice. A non-empty path selects
  that subtree, including future descendants admitted by its schema.
- Several subscriptions form the union of their selections. An ancestor interest
  covers descendant interests for replication, while each handle retains its own
  observation path. Reference-count overlapping interests across applications.
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

**Merge selection and projection are different operations.** Current
`AKeyedLattice.merge` ignores unknown incoming keys but retains unknown keys already
present in `own`. Preserve that non-destructive merge behaviour. A restricted
serving or observation view must explicitly project selected data; it cannot assume
that merging with a keyed lattice removes everything else. Absence in a partial
view is never a deletion instruction.

Selection must also respect indivisible merge and authentication boundaries. A
subscription may observe any navigable sub-path, including inside a whole-value LWW
register or signed tree. Replication acquires and merges the enclosing complete
value when required, then exposes the requested sub-path. It must not truncate a
signed value, sign a projection on the owner's behalf, or treat an LWW field as an
independent register. If group policy prohibits the necessary enclosing value,
replication cannot satisfy that interest and must report the limitation.

### Observation contract

`subscribe` establishes interest; `watch` optionally observes the resulting state.
Closing a watch leaves the subscription active. Each watch receives an initial
snapshot, followed by changed values at its path. Absence is represented by a null
value, not by a missing initial notification. The immutable CVM value `V` is the
snapshot; no public snapshot wrapper or revision token is required.

Registration captures the initial value and establishes subsequent delivery without
a gap in which a committed update can be missed. Notifications cover local atomic writes,
fork merges into the live tree and accepted remote merges. An ancestor replacement
also notifies a descendant watch if its value changes. A no-op merge does not.

Delivery is asynchronous, serial per watch and follows local installation order. Pending states
may coalesce to the latest snapshot, with bounded pending work per watch; callbacks
are state observation, not a durable event log. Initial delivery precedes subsequent
delivery. A slow or failing callback does not block state installation or another
watch; failures are reported through the application's error handler. Watch close
prevents further callback dispatch, though a callback already running may finish.

Implement observation at the authoritative state-installation boundary, not through
the inbound-message listener or publication callback alone. Attempted writes during
a CAS retry generate no events until installation succeeds. Capture the installed
immutable value for delivery; re-reading the live root while delivering a callback
could substitute a later state. Any sequencing bookkeeping remains internal to the
host. Fork-local changes remain private until
merged into the live tree.

### Atomic operations and synchronisation

Components use the existing cursor atomic operations. Views at overlapping paths
share the same authoritative state, so concurrent updates retry against the latest
enclosing value instead of replacing it with an earlier snapshot. Update functions
must be pure and may run more than once. IDs and externally visible effects must
not be generated inside a retryable update function.
One logical write resolves the context timestamp once and reuses it across retries.

Whole-value LWW is an explicit schema choice. A deep update inside such a value
must atomically read and modify its latest enclosing value, stamp the completed
write and cross any signing boundaries before installation. Two local components
changing different fields then preserve both changes. Never implement this as
`get`, modify, then `set`. Across replicas or detached forks, the selected LWW value
still wins as a whole; local atomicity does not introduce field-wise merge semantics.

| Operation | Completion contract |
|---|---|
| Cursor update | One local state change is atomically visible; publication may still be pending |
| Application `merge(value)` | Atomically merge an incoming root using the supplied lattice and effective cursor context; publication remains separate |
| Fork `sync()` | Merge one working snapshot into its immediate parent; concurrent state follows the declared lattice semantics |
| Application `sync()` | Run `beforeSync`, then publish one application snapshot through its cursor boundary |
| Host `sync()` | Persist and publish a host root snapshot, then notify configured replication services; no remote acknowledgement |
| Application `pull()` | One pull round for a captured set of current interests and configured routes has merged and published its successful responses; failures complete the stage exceptionally, without undoing successful merges |
| Host `flush()` | Complete the host store's physical durability barrier; call `sync()` first to include pending working state |

`pull()` is a local no-op without replication services. Configured replication with
no usable route reports unavailability rather than claiming remote synchronisation.
Ongoing replication operates while an interest is held; `pull()` provides an explicit
completion signal, not a claim that all peers are globally up to date. Host
`sync()` publishes all applications' already-installed writes because the host has one
authoritative root. A root snapshot already published cannot be rolled back by a
later propagation failure.

Atomic multi-path operations use one update or `transact` at a common ancestor.
Separate component calls are separate atomic operations. A fork is a mergeable
batch, not serialisable isolation for arbitrary cross-field invariants.

### Implementation scope and verification

The first implementation should add application lifecycle and host-owned interest
registrations, retain the current cursor/component machinery, then introduce root
change observation and adapt existing propagation groups to changing interests.
The current filter hooks alone do not implement acquisition demand or watches.
No per-component workers, replica roots or peer groups are needed.

Before adoption, verify lifecycle failure cleanup; overlapping interest removal;
initial snapshot/watch races; callback coalescing and failures; local and remote
notifications; same-path and overlapping-path atomic updates; concurrent deep LWW
updates beneath a signing boundary; partial-view handling; and unchanged signer and
timestamp semantics. Use futures or latches for concurrency tests, never sleeps.

## Key points

- Design the state tree first, then choose a merge strategy for each leaf and compose
  lattices bottom-up. `path()` and `zero()` on your lattice are what make cursors
  navigate and auto-initialise correctly.
- Wrap per-user data in `OwnerLattice` so every owner's slot is signed by that owner
  and forgeries are rejected on merge.
- One component, one path. An `ALatticeComponent` holds a cursor at exactly one lattice
  path and exposes domain operations; an `ALatticeApplication` composes regions at the
  root; a facade spanning paths contains components rather than being one.
- Applications merge incoming root values with `merge(value)`, which delegates to
  root lattice and cursor. Domain writes use cursors for atomicity; independent
  publication boundaries use the component's lattice to reconcile conflicts.
- Accept a `RootComponent` when attaching to a host. Explicit root create/open
  factories manage retained state. Domain components remain independent of `NodeServer`.
- Persist, sync and flush are three different boundaries: persistence writes cells,
  sync merges and publishes, flush is physical durability.
- Under union merges a removed key resurrects, so tombstone; for durable deletes use a
  whole-value LWW leaf.
- The `LatticeContext` is the write policy: who signs, what time it is, which signers
  may act for which owners. One logical write resolves the clock once.

## Architecture

```
Domain components        DLFSDrive, Social        one component per path
Application component    ALatticeApplication      root merge and optional region factories
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

**Compose structured records from keyed lattices.** Use `KeyedLattice` for named
children with different strategies in an index, or `StringKeyedLattice` for a
string-keyed hash map. These provide per-child merge, navigation and the appropriate
zero value. A custom `ALattice` is needed only for semantics the standard types
cannot express; it must implement `merge`, `path` and `zero` consistently.

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

**Compose from a generic host.** Hosted application factories accept `RootComponent`:

```java
public final class MyApplication extends ALatticeApplication<Index<Keyword, ACell>> {
    private final Social social;
    private final DLFSRegion files;

    private MyApplication(RootComponent<Index<Keyword, ACell>> host) {
        super(host);
        social = Social.connect(this);
        files = DLFSRegion.connect(resolve(Keyword.intern("documents")), host.store());
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
node.launch();
MyApplication networked = MyApplication.connect(node.getRootComponent());
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

Hosts, applications and domain components all extend `ALatticeComponent`. A
lightweight component holds a working cursor; publication boundaries also retain a
destination and baseline. Only `RootComponent` owns host persistence policy; DLFS
components additionally carry a store handed down explicitly from their application,
because streamed file writes checkpoint blobs before publication. The cursor is the
only connection back to application state: its internal links provide navigation,
atomic updates and fork merge-back. There is no second component-parent hierarchy,
and cursors themselves know nothing of storage. The component that owns an
owner-keyed or stamped level wraps the boundary itself, with `SignedCursor.forOwner`
or `StampedCursor.create` and the lattice it declares, so the boundary is visible
exactly where the domain structure places it and descendants never see it: `Social.user`
wraps the owner's slot, `Feed` never sees `SignedData`.

Constructors must copy caller-supplied path arrays. Long-lived application and region
components are fine; owner and session views should be held by the service that routes
them or recreated on demand, never accumulated in an unbounded cache.

**Write through cursors.** Use `updateAndGet` for read-modify-write; the lambda
receives `lattice.zero()` for an uninitialised path, so no null guard is needed:

```java
public void putPost(Blob key, String text) {
    long ts = cursor.getContext().currentTimestampValue();
    AHashMap<Keyword, ACell> post = SocialPost.createPost(text, ts);
    cursor.updateAndGet(feed -> feed.assoc(key, post));
}
```

This example writes an explicitly selected key. `Feed.post` allocates a free key
starting at the operation timestamp, advancing the key on collisions without
changing the post's timestamp. Key allocation is separate from the write clock.

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

## Component transactions

**Implemented:** `TransactionalComponent<V,C>` adds typed `transact`,
`transactAsync` and `transactLocked` operations. Social, feed, follow, identity,
node-record and DLFS region/drive components implement this interface. Existing
component subclasses can opt in without changing `ALatticeComponent<V>`.

```java
user.transact(u -> {
    u.feed().post("Hello");
    u.follows().follow(otherDid);
});

user.transactAsync(u -> u.feed().post("Another post"))
    .thenRun(this::notifyLocalCommit);
```

The callback receives a private component view. Its child components share the
same private state, and the final value is installed atomically through the live
cursor's signing and stamping boundaries. An exception discards the staged state.
Nested transactions on that view remain part of the outer transaction. Views and
descendants must not escape the callback; state access after completion or from
another thread is rejected. `sync()` on a transaction view is rejected as well.

Callbacks may repeat and must restrict their mutations to the supplied view.
External effects belong after synchronous completion or in a future continuation.
The transaction protects only its subtree: decisions must not depend on reads from
captured live components outside that subtree. Put such operations at their common
ancestor instead. Persistence may write immutable cells while staging; rollback
does not undo those physical writes or any external effects.

Conflict checks compare immutable values, using identity only as a fast path.
An equal replacement of the starting value does not require recomputation; an
unchanged result does not cause a write. However, a concurrent writer installing
the same proposed result does **not** count as completing this operation. Two
increments must both take effect. Signed ancestor changes and unrelated sibling
writes may require another commit attempt without rerunning the domain callback
when the subscribed value is still equal.

The host selects the concurrency policy at construction:

| Root | `transact` | `transactLocked` | Ordinary writes |
|---|---|---|---|
| `Root` (default) | Optimistic retries | Throws before invoking the callback | Original atomic operations |
| `TransactionalRootCursor` | Three optimistic attempts, then serial fallback | Callback runs once under root exclusion | Commits coordinate through a root lock |

```java
RootComponent<V> host = new RootComponent<>(lattice,
    Cursors.transactionalRoot(lattice.zero(), lattice, context), store);

// Alternatively, when creating a NodeServer:
NodeConfig config = NodeConfig.create().withTransactionalRoot(true);
```

The lock belongs to the shared root, covering every application view, ordinary
cursor write and incoming merge. It excludes writes to unrelated regions too;
reads continue to observe immutable committed values. Locked callbacks must stay
short and must not wait for another writer, publish state, or mutate a captured
live view. Forks inherit the capability but have independent state and locks.
`cursor.supportsExclusiveTransactions()` exposes the capability without probing
it by attempting a write. An ordinary root never acquires a write lock implicitly.

For a custom component, implement `TransactionalComponent<Value,MyComponent>`
and `withCursor(AForkableCursor<Value>)`, creating a fresh component with the same
domain metadata over the supplied cursor. Rebuild children against that cursor; do
not reuse cached live children. Application subclasses use the protected
`ALatticeApplication(lattice, cursor)` constructor. Fork and rebinding factories are
one line, e.g. `return new MyComponent(cursor.fork(LATTICE))`, passing the lattice
the component declares so the fork converges on sync; a component which explicitly
owns configuration, such as a DLFS store, passes it along itself.

`transact` returns `void`; `transactAsync` returns `CompletableFuture<Void>`.
Completion means committed locally, not persisted, published or acknowledged by
peers. Async work uses the shared virtual executor, or a caller-supplied executor
via `transactAsync(action, executor)`. Components create no workers of their own.
Cancelling a future neither rolls back a commit nor guarantees that an executing
callback stops. Publication and durability remain explicit operations.

## Fork and sync

A fork is an independent working copy; `sync()` merges it back through its cursor
parent with lattice semantics. Concurrent state is merged rather than treated as
a transaction conflict; verification, signing or storage can still fail. This is
separate from publication and durability:

```java
Social forked = social.fork();
forked.user(myKey).feed().post("Post 1");
forked.user(myKey).feed().post("Post 2");
forked.sync();   // merge into the enclosing working component
app.sync();      // checkpoint owners and publish to host
host.sync();     // persist and propagate the hosted root
host.flush();    // optional physical durability barrier, a host operation
```

Domain components expose `fork()` with their exact type when batching is a use case;
`sync()` is inherited from `ALatticeComponent`.

| Operation | Meaning | Moves a cursor? |
|---|---|---|
| `host.persist()` | Write the current root's reachable cells to the host store; return the store-backed value | No |
| `component.sync()` | Merge through the cursor parent; at the root, run publication policy | Yes, where merge selects a value |
| `host.flush()` | The store's physical durability barrier | No |

Incremental writers with their own store, such as DLFS drives, checkpoint at
intervals to swap direct references for store-backed soft references and relieve
memory pressure; they install the returned value themselves. Persistence neither
selects a retained root nor licenses garbage collection.

### Persistence

Persistence lives in two places and nowhere else:

- `RootComponent` owns the host store. It publishes the root on `sync()`, can
  `persist(value)` reachable cells without publishing, and provides `flush()`, the
  physical durability barrier. Working applications publish into this host before
  its own explicit sync persists or propagates their state.
- DLFS components carry a store explicitly, because streamed file writes checkpoint
  blob branches every 16 MiB before anything is published. `DLFSApplication` takes
  the host store at construction and hands it to regions, drive maps and drives;
  forks and transaction views keep the same store. The low-level `DLFS.connect` and
  `DLFS.open` factories accept an optional store for the same purpose. A drive
  without a store keeps file data on the heap until the root is published.

Cursors have no persistence API. A cursor is a reference to an immutable value with
atomic updates and navigation; storage, durability and publication are host concerns
attached at the root through its sync callback. With incremental checkpoints, a
transaction rollback or an abandoned fork may leave immutable cells in the store;
they are unreferenced and reclaimable, never a logical state change. Forking below
an owner's signing boundary still defers that signature until merge-back, so a DLFS
fork can make many edits, checkpointing blobs as it goes, and sign once on `sync()`.

## Write policy: `LatticeContext`

The context supplies signing capabilities and the write clock; typed ownership
lattices bind owner verification rules to the lattice instance. Install a context
on the application's cursor for application-local policy, or the host root cursor
for shared defaults. Descendants inherit their parent's context live. A fixed
context suits deterministic tests; an application policy
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
StampingLattice<ACell> state = StampingLattice.create(
    LWWLattice.create(JSONLattice.INSTANCE, tsFn),   // whole-value merge over JSON navigation
    (v, ts) -> v.assoc(KEY_TIMESTAMP, ts));          // stamp the context timestamp on every write

// the component owning the leaf wraps it before navigating below
AForkableCursor<ACell> leaf = StampedCursor.create(cursor.child(KEY_STATE, state), state);
```

`JSONLattice` provides structural navigation below the leaf, `LWWLattice` merges the
whole value by `:timestamp` so a smaller map with a newer timestamp replaces the old
one, and `StampingLattice` declares the stamp that the owning component's
`StampedCursor` applies so every deep write re-stamps the leaf. A delete is then a
read-modify-write that dissociates the key.

## Testing

`ApplicationPublicationTest` exercises independent applications publishing the
same signed subtree, forced host CAS conflicts, signature reuse on unrelated
retries, equal-timestamp local precedence, unchanged siblings, concurrent child
checkpoints, signing failure/retry and application-controlled acceptance. The
shared integration fixture also publishes posts from two independent Social
applications under the same owner. These tests require no signer at the host.

The cross-module [SharedApplicationsTest](../../convex-integration/src/test/java/convex/lattice/SharedApplicationsTest.java)
is a worked example of Social + P2P and DLFS + P2P sharing one `NodeServer`.
Both applications use the same P2P region definitions, independent user signing
contexts, and one deliberately shared node-record signer. The tests force a
concurrent update to that common signed record, then exercise separately filtered
publication views over real loopback connections. They verify that partial roots
merge in either order, Social publication excludes file data, and removing the
Social propagation group leaves DLFS/P2P running without deleting retained state.
Region assembly and group removal are explicit; this example does not implement
automatic subscription registration, schema composition or application shutdown.

Domain operations are also exercised directly: `Feed.post` retries while another
post, a new `P2PNodeRecord` and DLFS file changes reach the shared root. The test
checks that every change survives with valid owner signatures. A `SocialUser`
fork batches a post and follow into one signed update. A `DLFSDrive` fork stages
multiple channel writes before a single `sync`, preserving a concurrently written
sibling file; abandoning the fork leaves live state unchanged. Ordinary live NIO
streaming writes are incremental, so use a fork when the complete file must appear
atomically. These are separate atomic domain operations, not one transaction across
all three regions. Adding a P2P node record changes lattice state; establishing a
network connection remains a separate operation.

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
- [ ] Compose lattice primitives bottom-up; use keyed lattices for structured
      records and reserve custom `ALattice` implementations for new merge semantics.
- [ ] Wrap with `OwnerLattice` for per-user ownership; register under a root keyword.
- [ ] Supply the root lattice to an `ALatticeApplication`; optionally expose
      domain component factories at known paths. Root merge requires no components.
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
