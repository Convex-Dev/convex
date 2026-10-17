# Lattice Application Patterns

How to build an application on the Convex Data Lattice: design a lattice for the
data, wrap it in typed components over cursors, and connect it to a local store or a
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

**Policy is separate from mechanism.** A write needs a clock and a signer. The
context supplies both: one timestamp per logical write, used exactly as given, and
the signing capability this process has, possibly a wallet holding several
identities. Reusing a timestamp is a deliberate tie, which the local value wins.
Policy is held by the component doing the work, not by the data and not by the
storage.

**Cursors are the mutable layer, and only that.** A cursor is a reference to one
immutable value. It offers atomic update, compare-and-set, navigation into
sub-positions whose writes rebuild the enclosing value atomically, and private
drafts for multi-step transactions. A cursor knows nothing about lattices,
ownership, policy or persistence. It gives atomicity and navigation; meaning comes
from whoever holds it.

**Components give positions their meaning.** A component holds one cursor at one
path together with everything the cursor does not know: the lattice at that level,
the policy, and the domain operations such as post, follow or create drive. One
component per level of the data model. What a component is follows from a small set
of decisions made when it is constructed:

| Decision | Choices |
|---|---|
| Position | A path cursor handed down by the component that owns the level above. An application's position is the host root, handed to it by the host. |
| Navigation | A component creates its children one level below its own cursor, declaring each child's lattice so that a missing container is created with the right type. An application resolves whole region paths from the root with its root lattice. Boundaries are placed by the owning component, never discovered by navigation. |
| Publication | **Live**: writes go straight through the cursor and there is nothing to publish. **Staged**: a private working copy and a destination; sync reconciles the copy with whatever the destination now holds and publishes the result. A fork is a staged component constructed at the same position. |
| Boundary | For a level the component owns: none, stamp each write, sign each write, or sign once at publication. Signing once needs a staged component, and is the main reason to stage. |
| Reconciliation | The lattice merge at that position, with local edits winning ties, applied only when both the copy and its destination changed since they were last reconciled. A keyed lattice publishes only the keys it declares; the rest adopt the destination. |
| Policy | The context, clock and signers, resolved when the component is constructed. Later changes to the parent's policy do not reach it. |
| Incoming values | Whether the component is configured on the host to receive incoming values, and how it merges or routes what it accepts. Only a component at the root can be configured directly. |
| Transactions | Whether it offers atomic multi-step operations, by supplying a factory that rebuilds it over a draft cursor. |
| Store | Whether it carries a store for checkpoints before publication, as file components do. |

A lattice is needed by two of these decisions, declaring children and reconciling on
sync. A live component with no children needs none. The signed component is the
archetype of the staged choice: an unsigned working copy, a signed destination, one
signature at publication however many edits preceded it.

**Components must be robust to external change.** Whatever it chose above, a
component must allow for the value at its position changing under it at any time:
another component publishing into the same region, an incoming merge, or a fork
syncing. A live component achieves this with read-modify-write atomic updates; a
staged one by merging on sync. Neither overwrites. Components sharing a region must
agree on its lattice; two different rules at one path do not converge. That is why
region definitions are catalogued once and reused.

**An application is the component at the host root.** It owns the root lattice,
composed from the region lattices it understands, and the navigation from the root
to those regions; it creates domain components by handing them path cursors. It may
be configured on the host to receive incoming values, which it merges under its
lattice and ownership rules, and it decides when its work is published. The typed
base (`ALatticeApplication`) provides the root lattice, region navigation and
incoming values; it may be live in the host's root or staged over it. Plain code
that navigates from the root and groups domain components is doing the same job and
counts as an application. A host may hold several applications. Conceptually they
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

Component-owned working copies, a host that carries no lattice and a node that only
replicates are intentional revisions of CAD035, CAD036 and CAD045, which still
describe lattice-aware cursors, automatic cursor signing boundaries and a node
constructed from a lattice. Those CADs need corresponding updates before this design
becomes normative. Ownership remains governed by CAD038.

## Architecture

```
Domain components        DLFSDrive, Social        one component per path
Application component    ALatticeApplication      root lattice, region navigation, incoming values
Lattice host             LatticeHost              published root, optional store, publication policy
Cursor                   path, CAS, drafts        atomic state access
Optional host runtime    NodeServer               replication only
```

`LatticeHost` is the developer-facing host. It holds the published root and the
publication policy, and optionally a store whose lifecycle stays with the caller. It
knows no lattice. A node can host the same root and installs its replication
pipeline during launch; application code never knows a node exists.

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
public final class MyApplication extends ALatticeApplication<Index<Keyword, ACell>> {
    private final Social social;
    private final DLFSRegion files;

    private MyApplication(LatticeHost<Index<Keyword, ACell>> host) {
        super(LATTICE, host);
        social = Social.connect(this);
        files = DLFSRegion.connect(resolve(Keyword.intern("documents")), host.store());
    }
    // This application's rule for an incoming root: the lattice's default merge
    @Override public Index<Keyword, ACell> merge(Index<Keyword, ACell> incoming) {
        return cursor.updateAndGet(own -> getLattice().merge(getContext(), own, incoming));
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
    cursor.updateAndGet(feed -> (feed == null ? SocialLattice.FEED_LATTICE.zero() : feed)
        .assoc(key, post));
}
```

This example writes an explicitly chosen key. The feed's post operation allocates a
free key starting at the operation timestamp and advances the key on collisions
without changing the post's timestamp; key allocation is separate from the write
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

The application component declares the root lattice, the shape of the root with a
default merge for that shape, and owns the merge of incoming root values: it
delegates to the lattice merge, for the whole root or for the parts it wants, or
applies its own rule. Nothing assumes the lattice merge on the application's behalf.
It receives the host's root cursor and navigates below it with the root lattice; the
domain components it creates receive the cursor for their level and never navigate
back up. The global root is a keyed map, an `Index<Keyword, ACell>`: an application
composes the region lattices it understands at the globally defined region paths,
and the keyed merge ignores unknown incoming regions and retains unknown existing
ones. Root merge needs no domain components; a relay merges every region in its
lattice without constructing one. Component factories are a convenience, not a
registry.

```java
var host = LatticeHost.create();
var app = new LatticeApplication<>(selectedRegions, host);
host.configureApplications(List.of(app));
host.merge(incomingRoot); // delegated to the application's working copy
app.sync();              // merge the working copy back to the host
host.sync();             // optional host policy; otherwise a no-op without a store
```

The executable [favourites example](../src/test/java/convex/lattice/app/FavouritesApplicationTest.java)
is one application over an in-memory host. Each user's edits are signed immediately
through a signing cursor into the application's working copy, and only the
application's sync publishes to the host. A
[persistence variant](../src/test/java/convex/lattice/app/FavouritesPersistenceTest.java)
publishes to Etch and reopens the same application.

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
application supplies its own lattice. An application may override `merge` to hand
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
Social + P2P + DLFS example works this way.

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
delivery goes to each application's `mergeAt`, which resolves the component at that
path with `component(path)` and merges with that component's own `merge`, whether it
delegates to a lattice or does something else. An application hands back its own
component for the paths it has one for; for any other declared path the default is a
plain component merging with the lattice declared for that level. A path the lattice
does not declare delivers nothing, and the lattice tree ends at a signed envelope, so
callers supply the complete envelope at its slot.

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
// Each application is live at the host root and stages only the region it edits.
final class SocialApp extends ALatticeApplication<Index<Keyword, ACell>> {
    final Social social;
    SocialApp(LatticeHost<Index<Keyword, ACell>> host) {
        super(SOCIAL_ROOT, host.cursor());    // live at the root
        social = Social.connect(this).fork();  // staged copy of :social
    }
    P2PUser p2p(AccountKey user) { return P2PUser.create(this, user); } // live in :p2p
}

final class FilesApp extends DLFSApplication<Index<Keyword, ACell>> {
    final DLFSRegion files;
    FilesApp(LatticeHost<Index<Keyword, ACell>> host) {
        super(FILES_ROOT, host, Keywords.FS);
        files = dlfs().fork();                 // staged copy of :fs
    }
}

socialApp.social.user(ownerKey).feed().post("A staged post");
socialApp.p2p(ownerKey).node().setNodeInfo(nodeInfo); // immediately in the shared :p2p region
socialApp.social.sync();  // publishes :social into the host root, preserving :fs and :p2p
filesApp.files.sync();    // publishes :fs
host.sync();              // persistence and propagation are separate
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
precedence. Edits made during publication stay pending for the next sync. A live view
needs no sync to observe root changes; a staged component sees them when its policy
calls sync, which reconciles and publishes in one step.

Reconciliation at a signed slot follows the signed lattice's rule. When the result
equals either input's payload, that envelope is reused and no signer is needed. A
new payload requires an authorised signer, so a component that cannot sign for the
owner keeps the rule's selection. In the shared example the live application accepts
a remote signed feed, and the owner's staged component then combines that accepted
state with its private posts and signs the result. Divergent edits for one owner
belong in a component that can sign for that owner.

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
var signed = new SignedComponent<>(owners.signingFor(owner), signedSlot);
signed.setContext(ownerContext);
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
An application's `beforeSync()` hook is simply a place to call those syncs before it
publishes any root-level state of its own.

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
supplied view and leave external effects until after completion. The view must not
escape the callback. The transaction protects only its subtree: decisions must not
depend on live components outside it. Put such operations at their common ancestor.
Completion means committed locally, not persisted, published or acknowledged by
peers.

The host selects the concurrency policy at construction:

| Root | `transact` | `transactLocked` | Ordinary writes |
|---|---|---|---|
| Ordinary root (default) | Optimistic retries | Throws before invoking the callback | Original atomic operations |
| Transactional root | Optimistic, with serial fallback under contention | Callback runs once under root exclusion | Commits coordinate through a root lock |

```java
// When creating a node:
NodeConfig config = NodeConfig.create().withTransactionalRoot(true);
```

The lock belongs to the shared root and covers every view and write on it, including
unrelated regions. Reads continue to observe committed values. Locked callbacks must
stay short and must not wait for another writer or publish state.

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

## Write policy: `LatticeContext`

The context supplies signing capabilities and the write clock. Owner verification
rules are bound to lattice instances, not to the context. Install a context on the
application for application-local policy, or on the host for construction defaults.
Components resolve their policy when constructed; replacing a parent's policy later
does not change existing children. Cursors have no context, and transaction views
keep the component's policy when rebound. A fixed context suits deterministic tests;
an application policy resolves dynamically from a clock, wallet or key store and must
be thread-safe if shared. The `with...` methods override one capability and delegate
the rest.

```java
component.setContext(LatticeContext.create(null, myKeyPair));                  // runtime clock
component.setContext(LatticeContext.create(CVMLong.create(1000), myKeyPair));  // fixed clock
```

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

Verification rules belong to lattice instances. Different paths can compose
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
replace it, and nested boundaries each keep their own rules. The context provides
the signing capability and write clock, not verification. An application may still
decline to adopt data its lattice would accept. A predicate rejection or an
unresolved identity never falls back to accepting an arbitrary signer. The predicate
grants no signing capability: authoring still needs an authorised key in the
context.

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

Runtime subscriptions select from the application's lattice tree:

- An empty path selects the whole lattice. A non-empty path selects that subtree,
  including future descendants admitted by its lattice.
- Several subscriptions form the union of their selections. An ancestor interest
  covers descendant interests for replication, while each handle keeps its own
  observation path. Overlapping interests across applications are reference-counted.
- An application's lattice provides one merge meaning per path, and applications
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
  one application, immediate signing, explicit publication, with a persistence variant.
- [`ApplicationPublicationTest`](../src/test/java/convex/lattice/app/ApplicationPublicationTest.java):
  lightweight notes inside a staged, signed notebook published to its host path.
- [`TaskBoardApplicationTest`](../src/test/java/convex/lattice/app/TaskBoardApplicationTest.java):
  domain operations in transactions, with publication as a separate choice.
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
- [ ] Wrap with an owner lattice for per-user ownership; register under a root keyword.
- [ ] Supply the root lattice to an application component; optionally expose
      domain component factories at known paths. Root merge requires no components.
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

- `convex.lattice.ALatticeApplication`, `ALatticeComponent`, `LatticeHost`,
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
