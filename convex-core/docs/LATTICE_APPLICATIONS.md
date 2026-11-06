# Lattice application design

A lattice application defines how shared data is edited, accepted and merged.
It can run locally, retain state in a store, or replicate through a node. These
choices should not change its domain operations.

This document explains the design and its boundaries. Method contracts belong in
source Javadocs; runnable assemblies belong in the [examples](#examples).
[CAD045](https://docs.convex.world/docs/cad/lattice_apps) is the normative reference.

## The problem and the state model

Several nodes can hold and edit copies of shared data independently. They may be
disconnected, receive updates in different orders, or receive the same update
repeatedly. The application needs rules for combining those copies without a
central authority ordering every write. It also needs to preserve each owner's
authority over their part of the data.

The application state is an immutable CVM value: a tree of maps, indexes, sets,
vectors and signed envelopes. Editing produces a new value and atomically
replaces a reference. Unchanged branches can be shared between versions. This
makes a snapshot stable while other work continues, and gives persistence and
replication the same value representation as local application code.

A lattice describes how versions combine. Associativity permits grouping
exchanges differently, idempotence makes repeated delivery harmless, and
commutativity removes dependence on arrival order. Ownership and directional
tie rules constrain where these properties hold, as described below. Local
atomicity alone cannot settle conflicts between independently edited replicas.

## Responsibilities

| Part | Responsibility |
|---|---|
| Value | Immutable application state, including any signed envelopes |
| Merge rule | Decide how two versions of a value combine |
| Cursor | Atomic access to a value or a position within it |
| Component | Domain operations, acceptance policy and publication at one boundary |
| Application | Compose components, choose what incoming state to receive and when to publish, and make its domain's network decisions |
| Host | Hold the published root and provide retention and durability operations |
| Node | Deliver and replicate published state, doing the network work its applications' decisions call for |
| Service | Assemble and run the host, its applications and networking, and shut them down in order; see [service anatomy](#service-anatomy) |

A component binds storage to meaning. Its cursor provides atomic operations;
its domain logic supplies container creation, validation, signing, timestamps
and merge behaviour. Reusable lattice classes can supply merge rules, but an
application does not need a second object tree mirroring its components.

This separation keeps storage reusable. A cursor cannot choose the right merge
for a domain, decide who may sign, or know when a user intends to publish a draft.
Those decisions belong to the component that understands the value. Conversely,
a merge rule describes values independently of their location, so the same rule
can serve local editing, reconciliation and incoming replication.

The host knows neither domain paths nor merge rules. It delivers incoming values
to registered applications, which decide what to accept. With no receiving
applications, a host ignores incoming state. Networking wraps the same host and
uses the same delivery boundary.

Clocks, signers and authority resolvers are explicit component dependencies.
They are retained when constructing private views; they are not inherited from
a host or discovered through a cursor.

Explicit dependencies allow a read-only replica, an authoring service and a test
with a controlled clock to use the same component design. Merely moving a
component under another host must not grant it new signing authority or change
its interpretation of incoming data.

## Data and merge boundaries

Design the state tree around independently mergeable values. Choose a rule for
each boundary: union for additive collections, a register for whole-value
replacement, or a domain-specific merge. Components sharing a region must agree
on its representation and rules. Reuse definitions from the
[region catalogue](LATTICE_REGIONS.md).

Compose rules from the leaves upward. A record can give each named field its
own rule; a map or index can apply one family of rules to its entries. The rule
may depend on the entry key: an owner slot must verify that owner, and a
content-addressed entry must match its identity. Whole-container merge and
delivery to an individual entry must agree on that meaning.

Collection operations and replication semantics are separate choices. Reusing
a set or map component gives atomic local edits without forcing every field
containing that collection to merge by union. Navigation below a register is an
editing convenience; it does not make those fields independently mergeable.

Reusable components are therefore placement-free: one built over a cursor takes
its replication from where that cursor sits and adds no merge of its own, so the
same component can be private in a `:local` record or published in an owner's
signed value. `AccessList`, the standard component for blocklists and allowlists,
is one: an `Index` from each key to a small optional record, read against an
injected clock. Inside a register such as a `:local` record, removing a key
carries over with the record, with no tombstone. An allowlist and a blocklist are
separate lists, and what each refuses or admits is the application's decision.

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

This supports applications evolving independently. A component validates what
its operations and merge rule need, while preserving unrelated data. Recognising
a new field must not be a prerequisite for forwarding an otherwise acceptable
signed record. Construction and reads do not create empty replicated state;
the component creates a missing container only when a changed write needs it.

## Composition and sharing

An application is a component assembled at the host root. It usually groups
named regions, each handled by a child component. Region paths belong to this
assembly; domain components receive storage at their own position. The same
component can therefore live at a default region, a custom path, or inside
another application. Nodes can participate in different sets of regions while
agreeing on the definitions of the regions they share.

A component can be live in its supplied storage or hold a staged working copy.
Live edits are immediately visible there. Staged edits become visible at their
destination only when the component publishes. This choice belongs to the
component's construction and is independent of whether its host has networking.

The main design choices for a component are:

| Choice | What it determines |
|---|---|
| Position and extent | Which value it owns, which children it exposes and which siblings it preserves |
| Live or staged state | Whether edits are immediately shared or remain private until publication |
| Merge and acceptance | Which incoming values are meaningful and how competing versions reconcile |
| Authoring boundary | Which complete value is stamped or signed, and whether signing happens per edit or per batch |
| Atomicity | Which related operations must commit together |
| Dependencies | Which authority information, signers, clocks and large-data storage it needs |

Typical publication boundaries are a whole application, a domain region and an
owner's signed value. The choice follows the unit of work: a region can hold an
unpublished import, while an owner's draft can collect several edits into one
signature. Boundaries can nest. An aggregate that must publish atomically owns
its complete merge rule; placing children under it does not by itself combine
their publication boundaries.

Several applications can share a host without sharing private working state.
They publish only the regions they own and preserve unrelated regions. Sharing
the same component instance also shares its pending changes; either application
can publish them. Separate staged components at the same path reconcile their
independent copies when publishing.

A region container (`Regions` or `SingleRegion`) is a component, at the root or
nested, that routes incoming values to its children and publishes them. It has
no separate aggregate copy to overwrite the container afterwards. Publishing
children is sequential, not atomic across regions: earlier successful work
remains if a later child fails. An operation needing an atomic aggregate belongs
at a common ancestor that owns that aggregate.

An application made only of regions extends a region container and is registered
with the host as it is. One with root-level behaviour of its own, such as a merge
across its regions or a private copy of the whole root, extends
`ALatticeApplication`, holding a region container where that helps. The host
registers either, since it takes any root component.

Building an application does not register it. The main class does both in one
expression, `host.addApplication(new App(host, …))`. A component built over the
host's cursor but never registered is a view: it reads and writes the root, but
the host delivers nothing to it.

Components must tolerate their destination changing through another view,
publication or incoming merge. Live views observe shared changes directly;
staged views reconcile when they publish. Keeping a component open does not
give it exclusive ownership of the value at its path.

## External component paths

`ALatticeComponent` exposes `value()`, `exists()` and `resolve(AString... path)`.
The resolution hook is `resolve(AString[] path, int start, int end)`, with an
exclusive end index. It returns the destination component; an empty slice returns
the current component. Parents can pass the same array with advanced indices to
children. The array is borrowed, so a component retaining segments must copy them.
Resolution creates no stored state and keeps children on the current editing view,
including within a draft or transaction.

The base supports only the empty path. Each parent defines its external namespace
and selects the component responsible for the rest of the path. External addressing
is independent of the `child(ACell)` hook used for incoming merge delivery.

Two value components supply reusable addressing policies:

| Component | Segment interpretation |
|---|---|
| `ValueComponent<V>` | Literal `AString` keys, without inspecting the current value during resolution |
| `JSONValueComponent<V>` | Keys as the JSON rendering shows them, explicitly selected by the parent |

The JSON variant lets a client address what it reads in the
[CAD044](https://docs.convex.world/docs/cad/json) rendering of a value, for these
containers and key types:

- In a vector or list, a segment is a position: a non-negative decimal integer
  with no sign, whitespace or leading zero.
- In a map or Index, a segment names the first existing key, including one holding
  nil, from: the string itself; the keyword, then the symbol, with that name; then
  the blob, address and integer that `Blobs.parse`, `Address.parse` and
  `AInteger.parse` read from it. Blob keys therefore take hex with or without `0x`.
- A segment naming no existing key keeps its string key, so a write creates a
  string field, as parsing JSON would.
- Sets and other values have no addressable children.

Selecting this variant declares the intended addressing policy; it does not
validate the whole value as JSON.

JSON resolution selects keys from one snapshot. A returned component keeps those
keys: resolve again to interpret a later container shape. A resolved position
becomes absent if its container becomes a map, as does an integer map key if its
container becomes a sequence, and writes through such a handle refuse the
incompatible container. Neither variant creates missing intermediate containers or
supplies a merge rule. A parent needing those behaviours provides them explicitly.

`value()` uses the strict cursor read contract from
[CAD035](https://docs.convex.world/docs/cad/cursors): absent or unresolvable
locations throw `MissingValueException`, while stored nil returns null.
`exists()` is true for stored nil. For an optional read use
`cursor().getOrDefault(fallback)`. Resolution does not grant signing authority or
change the publication rules of
[CAD045](https://docs.convex.world/docs/cad/lattice_apps).

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

This lets an application choose immediate publication, a batch boundary or an
explicit user action without changing its domain operations. It also prevents
an unrelated network event or another application's save from accepting work
that is still under review.

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

Acceptance does not define disclosure. A component can ignore unknown incoming
regions while preserving unknown regions already held locally. That does not
produce a restricted serving view: disclosure needs an explicit projection.
Absence in a partial view is not evidence that the corresponding data was deleted.

## Forks and transactions

A component fork is a reusable private working copy. Publication compares it
with its destination and their last reconciled value. If only one side changed,
that side is adopted. If both changed, the component's merge rule reconciles
them. Local edits occupy the local side of directional tie-breaking rules.
Reconciliation and signing must succeed before the destination changes.

Forks support work that has its own lifetime: editing a draft, importing data,
or preparing a signed batch. The domain operations should remain the same on a
live view and a private view. Their storage and publication boundaries differ,
not the meaning of actions such as adding a favourite or editing a file.

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

Even a single local edit must atomically transform the latest enclosing value.
Two live views editing different fields of one register should preserve both
changes; reading an old record and replacing it later would lose one. This local
guarantee does not turn a whole-record replica merge into a field-wise merge.
For a consistent read across several published paths, use one immutable root
snapshot rather than separate reads that may span different versions.

A host fork has a different purpose: it is a detached host with its own
applications and optional store. It has no host-level merge rule. Synchronising
it exchanges roots through each host's normal application delivery, like two
nodes without a network. Saving a private host does not perform that exchange.
Its store must be separate from the origin's; reopening saved state does not
restore an implicit relationship with the origin.

Separate host storage is useful for large or durable drafts that must outlive a
session. Exchanging roots must carry their reachable cells into the receiving
store, so accepting state does not leave it dependent on a disposable draft
store. Application publication on each side remains a separate decision.

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

Authorising several writers for an identity does not coordinate those writers.
They still need compatible merge and timestamp policies for the entire signed
value. Signing a subtree makes the owner responsible for that subtree; neither
transport identity nor permission to connect grants authority to author it.

Local authoring uses the same owner and payload rules as incoming acceptance.
A signer cannot change the requested payload or substitute its own authority
policy. Signed editing views keep this enforcement around ordinary domain
operations. Unsigned drafts allow a batch to be signed once when it reaches its
signed destination; intermediate drafts remain private.

Clocks apply to local writes. Incoming values retain their signed timestamps;
merging does not re-stamp them. Resolve authority data outside atomic operations
so verification uses locally available information. Updating that information
affects later checks, without retroactively deleting previously accepted state.

One logical write uses its chosen timestamp through atomic retries. Reusing a
timestamp creates a tie rather than an implicit increment. This keeps time
policy explicit and reproducible; applications needing ordered delete and
recreate operations must supply timestamps that express that order.

Merge is the acceptance boundary for incoming data. Recognising the shape of a
candidate accepts neither its signature, its owner nor its contents, so each
component's merge enforces its own rules. Raw cursor access remains trusted
application code; component boundaries are not a sandbox between code sharing a
process.

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

A component with working state of its own, given a persistence function when
built, persists it with `persist()`. An application holding a private copy of the
root is built as `super(host.cursor().fork(), host::persist)`. Its cells go to the
store and need not stay in memory, but nothing is published, signed or retained as
the root, so it survives a restart only once synced and checkpointed. The component
never holds a store: it persists through the function it was built with. A component without one refuses,
since its state is its parent's. Which components persist, and when, is the
application's policy.

State that is not meant to be shared externally, such as configuration or what
a node has seen of others, lives in the owner's slot in `:local`, one record per
service; it may still be replicated for backup. An
`ALocalComponent` edits a private copy of its record and writes it into the slot
with one signature when the application syncs it, so the application decides how
many edits share a signature.

The service chooses when to promise durability. It can checkpoint before
acknowledging a durable request or, when delayed durability is acceptable, have
the host sync and flush on a schedule. The host never chooses which applications
sync: the service names them. Background workers must expose failures, and
closing the host always ends with a full save; see
[persistence](../../convex-peer/docs/PERSISTENCE.md#logical-publication-and-durability).
At shutdown the service saves what it keeps in a final checkpoint, which its node
sends to peers as it closes; see [shutdown](../../convex-peer/docs/SHUTDOWN.md).

Separating these boundaries avoids forcing every edit to pay for a disk barrier
or a network round trip. It also makes completion precise: a local edit, retained
snapshot, durable save and remote delivery are different promises. None means
that every replica is globally up to date. Failure in replication cannot undo
an already completed local publication.

Networking does not decide that saving policy. Attaching or closing replication
neither publishes application drafts nor supplies a durability guarantee; closing
sends only what the host has already published. A host and its applications
remain usable after networking closes. Propagator groups
choose what to serve and whom to exchange with; delivery still passes through
the application's acceptance rules.

Large-data components such as DLFS can write immutable blob branches to an
explicitly supplied store during editing. This limits memory use without
publishing the file tree. Abandoned edits may leave unreferenced cells, but do
not change retained application state.

See [persistence](../../convex-peer/docs/PERSISTENCE.md) and
[lattice networking](../../convex-peer/docs/LATTICE_NETWORKING.md) for the runtime
contracts.

## Service anatomy

A service is the process that runs lattice applications: it holds their data and,
when networked, replicates it. Everything that runs belongs to the service, which
starts each part, owns it and stops it.

| Part | Role | Classes |
|---|---|---|
| Main class | Reads configuration, opens the store and keys, assembles the other parts, launches them and shuts them down in order | One per service, such as `convex.dlfs.Main` |
| Store | Holds cells and the retained root; the other parts borrow it | `EtchStore` |
| Host | Holds the published root and its durability, and delivers incoming values to every application | `LatticeHost` |
| Application | Owns one domain: its data, how it merges, the decisions the network acts on for it, and whatever else the domain needs | One per domain, such as `DLFSApplication` or `P2PApplication` |
| Node | Does the network work for its propagator groups and listeners: the node's identity, peer name lookup, dialling, admission, pulls and retries | `LatticeNode` |
| Propagator group | Publishes one view of the host, chosen by its filter, to its own peers within its own limits | `LatticePropagator` |
| Listener | Accepts connections on one port and assigns each to a propagator group | `LatticeListener` |

The main class is the only part written for one deployment. A new service starts
from a copy of an existing one and changes what it assembles; the other parts are
reused as they are. The main class makes the service's choices:

- **Applications.** Several applications share one host root, with or without
  shared regions, as [composition and sharing](#composition-and-sharing) describes.
- **Propagator groups.** Each publishes what its filter keeps, so a propagator
  group for some regions is one whose filter keeps only those. Filters apply to
  publication only: what a propagator group receives is delivered to every
  application, which accepts what it understands. Applications offer projections
  of their own regions to build filters from.
- **Listeners.** Each listener assigns its connections to the propagator groups it
  serves, so a propagator group reached separately, such as a replication port for
  trusted peers, gets a listener of its own.
- **Network decisions.** Which applications decide the network of each propagator
  group, as [network decisions and mechanics](#network-decisions-and-mechanics)
  describes. A propagator group that no application decides for connects only to
  the peers the main class names.

A service's own front ends and APIs, such as DLFS's WebDAV server, are outside
this anatomy; how they are built and owned is the implementer's choice, often the
main class or the application.

The store opens first and closes last. The host and its applications open before
networking and stay usable after it closes. The node, its propagator groups and
listeners run from launch to shutdown, in the order
[shutdown](../../convex-peer/docs/SHUTDOWN.md) gives. A service that runs within
one block opens the store, host and node in one `try`-with-resources header, which
closes them in reverse, and saves as the block's last step; that document shows the
block.

The smallest networked service runs one application on a host, and a `LatticeNode`
with one propagator group that publishes the whole host and keeps the peers the
configuration names; when configured to, it serves every inbound connection on the
node's own listener. `convex.dlfs.Main` has this shape, with a WebDAV front end.
Everything else is added by the main class: propagator groups with their own filters
and limits, listeners with their own selectors, and the applications that decide
each propagator group's network.

### Network decisions and mechanics

The line between an application and the node is between deciding and doing. An
application makes the decisions its domain calls for: which nodes to connect to,
which to join through, what to pull from a new peer, which keys and addresses to
refuse, which key a delivered value vouches for as its sender's, and how its
messages are checked. The node does the work: sockets, dialling, retries and
backoff, challenges, pulls, route upgrades and timers. Because replication's
network work stays in the node, an application's data and merging work with no
network and after networking closes. An application can still hold other things
its domain needs, such as workers, caches or a front end.

The node connects the two by asking questions of the applications that decide a
propagator group's network, and reporting events to them:

| The node | The application |
|---|---|
| Asks which peers the propagator group should know of | Answers with keys and addresses |
| Asks which peers to join through when it has no route | Answers with its seeds |
| Asks what to pull from a newly admitted peer | Answers with paths: its [interests](#intended-lifecycle-interests-and-observation) |
| Asks whether a key or address is refused | Answers from its blocklist |
| Asks which key, if any, an inbound value vouches for as its sender's | Answers with the key, which the node then challenges the sender to prove |
| Asks how a peer name resolves | Answers with the addresses it knows |
| Reports that it is listening at an address | Publishes what it advertises there |
| Reports that a key was proved, or a dial failed, at an address | Updates what it remembers of contacts |
| Reports that a route opened or closed | Adjusts what it keeps about that peer |
| Delivers a message of the application's type, and sends one to a key when asked | Handles or relays it |

When an answer changes, as when an application blocks a node, the node asks again
and acts on the new answer.

These questions and reports are intended and not implemented. They are
implementation design within the rules of
[CAD036](https://docs.convex.world/docs/cad/lattice_node): applications may turn
discovery into desired peers for a propagator group, and only a live proof of
possession of a key an application admits upgrades an inbound connection to an
authenticated route. Today a class in the application's package connects its
decisions to the node by installing the node's lower-level handlers: the connection
manager's admission and contact handlers and discovered peers, the propagator
group's inbound value listener and message handler, and the node's peer resolver.

### P2P as an example

The P2P package's service, which `convex p2p start` runs, has these parts:

- **Main class:** `P2PNode.main` reads a JSON5 configuration of node settings,
  `seeds`, `store` and `keypair`. `P2PNode.serve` launches the node and joins the
  network through the seeds; at shutdown it syncs the application and checkpoints
  the host, then closes the node, host and store.
- **Store:** the Etch file the configuration names, or a temporary store.
- **Host and application:** `P2PNode.create` opens the host from the store and
  registers one application, `P2PApplication`. It owns the regions `:p2p` (node
  records), `:id`, `:social` and `:local`, and the bundled application regions of
  `P2PLattice.NODE_ROOT`. It holds P2P's decisions: the validated node records and
  this node's own, the blocklist, seeds, contact records and the social owners the
  node wants.
- **Node, propagator group and listener:** `create` builds a `LatticeNode` whose
  identity is the node key, with one propagator group that publishes everything
  except `:local`, with `:social` cut down to the owners the node wants.
  `serveAllInbound()` assigns every connection on the node's own listener to that
  propagator group, except one from a blocked address.
- **Network decisions:** the `P2PNode` instance connects `P2PApplication`'s
  decisions to that propagator group through the node's lower-level handlers. It
  publishes the node's own record once the listener has its port, and offers the
  nodes in validated records as connection candidates. It joins through seeds
  whenever it has no route, pushes its record to each admitted peer and pulls
  `:p2p`, `:id` and the social owners it wants from it. It challenges an inbound
  connection that delivers a node's own valid record and, once the sender proves
  that node's key, makes it an authenticated route to that node. It keeps contact
  records, applies the blocklist and carries point messages. Joining through seeds, refusing, remembering contacts and pulling from
  new peers are generic network work that `P2PNode` does itself.

`P2PNode` therefore does the main class's work (`main`, `serve` and the assembly in
`create`) as well as connecting P2P's decisions to the node. In the intended design,
`P2PApplication` answers the node's questions and `P2PNode` keeps only the main
class's work. A service with other applications cannot yet use P2P's decisions on a
host it assembled, because `P2PNode` opens its own host and builds its own node.

## Intended lifecycle, interests and observation

The following lifecycle, subscription and observation model is not implemented.
Its API remains open and needs corresponding CAD updates before becoming
normative.

An application should be usable against local state before any peer is
reachable. Constructing a component should not start workers, open connections
or register observers; workers and connections belong to the node, as
[network decisions and mechanics](#network-decisions-and-mechanics) describes. An
explicit running lifetime, which the service starts and ends, would hold the
application's registrations, such as observers and interests; ending it would
release them without closing a shared host or store. This keeps composing
applications independent of starting services.

An interest would request acquisition and continued replication of a subtree
already defined by the application's components. A known path with no value is
valid; an unknown path cannot invent a new schema or merge rule. Several
interests form a union, with overlapping acquisition shared across applications.
An ancestor interest covers descendants for replication while individual views
retain their own observation paths. Closing the last interest can stop network
work, but cannot delete retained data or bypass the store's retention policy.

Interest expresses demand, not permission to read, write or publish. Admission
and disclosure policies still govern what can be obtained. Selection must also
respect indivisible values: observing a field inside a signed record or a
whole-value register requires acquiring and verifying its enclosing value.
Replication cannot truncate the signature or treat that field as an independent
register. If disclosure policy prohibits the enclosing value, the interest
cannot be satisfied through that route.

Observation would be optional and separate from acquisition. A watch should
receive an initial snapshot, including absence, followed by changes at its path,
without a gap at registration. Local edits, accepted remote changes and published
drafts all count; replacing an ancestor matters only if the observed value
changes. Asynchronous notifications should preserve local installation order
per watch, isolate slow or failing observers, and allow intermediate snapshots
to coalesce. They expose current state, not a durable event log. Closing a watch
alone would not withdraw the underlying replication interest.

An explicit pull would describe a completed exchange over selected interests
and available routes, not global synchronisation. Unavailable routes and partial
failure must be distinguishable from success; successful earlier merges need
not be rolled back. Receiving state would still not authorise publication of
private drafts.

Other open questions include stronger types for owner-bound storage and the
final component/application vocabulary. The [design history](LATTICE_DESIGN_LOG.md)
records these choices and their evolution; they should not be mistaken for
additional APIs already available to implementers.

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
| Atomic mail deposits and receipts | [MailTest](../src/test/java/convex/lattice/mail/MailTest.java) and [MailDeliveryTest](../../convex-peer/src/test/java/convex/node/app/MailDeliveryTest.java) |
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
