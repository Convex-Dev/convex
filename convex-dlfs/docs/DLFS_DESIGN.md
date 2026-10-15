# DLFS Design

Decentralised Lattice File System — a content-addressed, CRDT-mergeable filesystem
built on Convex immutable data structures with Java NIO compatibility.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│           WebDAV / HTTP Clients (curl, OS file manager)      │
└──────────────────────┬───────────────────────────────────────┘
                       │  HTTP (GET, PUT, DELETE, MKCOL, PROPFIND, MOVE, COPY)
                       │
┌──────────────────────▼───────────────────────────────────────┐
│              DLFSServer / DLFSWebDAV                         │
│          (Javalin HTTP + WebDAV protocol)                    │
│  - URL routing: /dlfs/{drive}/{path}                         │
│  - JWT authentication (Ed25519 bearer tokens)                │
│  - DLFSDriveManager: local identity-to-component routing     │
│  - PropfindResponse: RFC 4918 XML responses                  │
└──────────────────────┬───────────────────────────────────────┘
                       │  java.nio.file API
                       │
┌──────────────────────▼───────────────────────────────────────┐
│                    FileSystem layer                          │
│              (Java NIO FileSystem API)                       │
└──────────────────────┬───────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────┐
│                  DLFileSystem / DLFSLocal                     │
│           (Mutable Java interface to DLFS)                   │
│  - Wraps AForkableCursor<AVector<ACell>>                     │
│  - Provides Java NIO Path operations                         │
│  - fork() / sync() for batch operations                      │
│  - replicate(other): convenience method                      │
└──────────────────────┬───────────────────────────────────────┘
                       │
                       │ Uses cursor.updateAndGet() / merge()
                       │
┌──────────────────────▼───────────────────────────────────────┐
│       AForkableCursor<AVector<ACell>> Cursor                   │
│      (Atomic mutable reference; merges via DLFSLattice)       │
│  - Root: standalone drive                                     │
│  - PathCursor: child of a drives map, shaped by DLFSLattice   │
│  - ForkedCursor: isolated fork merging with DLFSLattice       │
│  - merge(DLFSLattice, other): lattice merge (always succeeds) │
│  - fork(DLFSLattice) / sync(): isolated working copies        │
└──────────────────────┬───────────────────────────────────────┘
                       │
                       │ Pure functions on AVector<ACell>
                       │
         ┌─────────────┴─────────────┐
         │                           │
┌────────▼──────────┐      ┌─────────▼────────────┐
│    DLFSNode       │      │   DLFSLattice        │
│  (Pure functions) │      │ (Lattice semantics)  │
│                   │      │                      │
│ - merge(a, b)     │◄─────┤ - merge()            │
│ - navigate()      │      │ - zero()             │
│ - updateNode()    │      │ - checkForeign()     │
│ - createFile()    │      │ - path()             │
│ - getUTime()      │      │                      │
└───────────────────┘      └──────────────────────┘
```

### Layer Responsibilities

**DLFSNode** — Pure utility functions for DLFS data structures. No mutable state,
no lattice awareness. `merge(a, b)` is deterministic: uses max of node timestamps
for conflict resolution. `navigate(root, path)` traverses the filesystem tree.

**DLFSLattice** — Lattice abstraction (`ALattice<AVector<ACell>>`). Delegates to
`DLFSNode.merge()` for the actual merge logic. Provides `zero()`, `checkForeign()`,
and `path()` for the lattice cursor hierarchy.

**DLFSLocal** — Mutable Java NIO filesystem backed by a lattice cursor. All file operations
(`createFile`, `write`, `delete`, `createDirectory`) go through `cursor.updateAndGet()`
with pure DLFSNode functions. Merges use `cursor.merge(DLFSLattice.INSTANCE, other)`;
the cursor knows nothing of the lattice, so `DLFSLocal` supplies it for merges and
forks. The cursor can be a `Root` (standalone drive), a child of a drives map declared
with `DLFSLattice` as its shape, or a `ForkedCursor` (isolated working copy).

### Key Files

| File | Location | Purpose |
|------|----------|---------|
| `DLFSNode` | `convex-core/.../lattice/fs/DLFSNode.java` | Pure merge/navigate/update functions |
| `DLFSLattice` | `convex-core/.../lattice/fs/DLFSLattice.java` | ALattice implementation |
| `DLFSLocal` | `convex-core/.../lattice/fs/impl/DLFSLocal.java` | Mutable filesystem (Java NIO) |
| `DLFileSystem` | `convex-core/.../lattice/fs/DLFileSystem.java` | Abstract base with timestamp |
| `DLFS` | `convex-core/.../lattice/fs/DLFS.java` | Factory methods |
| `LatticeHost` | `convex-core/.../lattice/LatticeHost.java` | Generic store-backed application root and persistence policy |
| `ALatticeApplication` | `convex-core/.../lattice/ALatticeApplication.java` | Root-level application composition and durability boundary |
| `DLFSApplication` | `convex-dlfs/.../dlfs/DLFSApplication.java` | Application component composing DLFS at a configured path |
| `DLFSRegion` | `convex-dlfs/.../dlfs/DLFSRegion.java` | Physical multi-owner DLFS region at an arbitrary lattice path |
| `DLFSDrives` | `convex-dlfs/.../dlfs/DLFSDrives.java` | One owner's map of named drives |
| `DLFSDrive` | `convex-dlfs/.../dlfs/DLFSDrive.java` | Per-drive component and hosted NIO adapter |
| `DLFSServer` | `convex-dlfs/.../dlfs/DLFSServer.java` | Javalin HTTP server lifecycle |
| `DLFSWebDAV` | `convex-dlfs/.../dlfs/DLFSWebDAV.java` | WebDAV route handlers |
| `DLFSDriveManager` | `convex-dlfs/.../dlfs/DLFSDriveManager.java` | Local drive routing, mapping and service adapter |
| `PropfindResponse` | `convex-dlfs/.../dlfs/PropfindResponse.java` | RFC 4918 XML response builder |

## WebDAV Layer

The `convex-dlfs` module provides a WebDAV-compatible HTTP server that exposes DLFS
drives to standard clients — OS file managers, `curl`, WebDAV libraries (Sardine),
and any HTTP client.

### Server Stack

```
DLFSServer (transport lifecycle only)
  ├── Javalin (HTTP framework, virtual threads via Jetty)
  ├── AuthMiddleware (optional Ed25519 JWT bearer tokens)
  ├── DLFSWebDAV (route handlers)
  └── DlfsMcpTools
        └── DLFSDriveManager (local routing policy)
              ├── identity → DLFSDrives component → DLFSDrive NIO view
              └── explicit ephemeral fallback for tests/demos only
```

`DLFSServer` owns the Javalin lifecycle. It binds to loopback by default, applies a
bounded request size, wires authentication middleware (optional), registers WebDAV
routes, and configures Jetty with minimal platform threads (1 acceptor + 1 selector)
since request handling uses virtual threads. Cross-origin access is not enabled by
default.

### Application Component Stack

The developer-facing lattice application stack is independent of HTTP and of the
particular host implementation:

```
LatticeHost<V>                  store persistence, root publication, flush
└── ALatticeApplication<V>        complete-root application composition
    └── DLFSApplication<V>        configured DLFS region path
        └── DLFSRegion            physical multi-owner lattice region
            └── DLFSDrives        long-lived component for one owner
                └── DLFSDrive     named drive plus local NIO view
```

A local bootstrap explicitly obtains the root with `LatticeHost.open(...)`.
A networked bootstrap obtains the same root abstraction
from `NodeServer.getHost()`. No application branch depends on `NodeServer`.
`root.sync()` publishes through host policy; component sync stops at its destination.
`flush()` is the separate
store durability barrier. `DLFSDriveManager` is outside this ownership tree because
it is service routing policy and may map identities across several regions or roots.

### URL Structure

```
/dlfs/                              → drive listing
/dlfs/{drive}/                      → drive root directory
/dlfs/{drive}/path/to/file.txt      → file within drive
```

The first path component after `/dlfs/` is always the drive name. Everything after
is the file path within that drive's filesystem. Drive names are per-identity — two
authenticated users can each have a drive called "home" without conflict.

### WebDAV Methods

| Method | Drive-level | File-level |
|--------|-------------|------------|
| `GET` | List drive names | Read file content / directory listing |
| `PUT` | Rejected (403) | Write file (201 Created / 204 No Content) |
| `DELETE` | Delete drive | Delete file (tombstone) |
| `MKCOL` | Create drive | Create directory |
| `PROPFIND` | Drive list (207 Multi-Status) | File/directory properties (207) |
| `MOVE` | Rename drive | Move file (read + write + delete) |
| `COPY` | — | Copy file |
| `HEAD` | Exists check | File metadata (Content-Type, Content-Length) |
| `OPTIONS` | DAV capability discovery | DAV capability discovery |
| `LOCK`/`UNLOCK` | 501 Not Implemented | 501 Not Implemented |
| `PROPPATCH` | 501 Not Implemented | 501 Not Implemented |

Only DAV class 1 is advertised. LOCK, UNLOCK and PROPPATCH fail explicitly: returning
success without enforcing locks or storing properties would give clients a false
correctness guarantee. File MOVE/COPY is supported within one drive; directory
MOVE/COPY currently returns 501 rather than performing a partial operation.

### Authentication

Authentication is optional and controlled by the `PeerAuth` passed to
`DLFSServer.createAuthenticated()`. `createWithAudience()` derives the standard
did:key audience policy from an `AKeyPair`. When authentication is configured:

1. `AuthMiddleware` extracts Ed25519 JWT bearer tokens from the `Authorization` header
2. The token's `sub` claim provides the user identity as a DID string (`did:key:...`)
3. `DLFSWebDAV.getIdentity()` reads this from the request context
4. `DLFSDriveManager` uses the identity to route to that user's drives

When authentication is configured, `requireAuthForWrites` is enabled by default. Mutating operations (PUT,
DELETE, MKCOL, MOVE, COPY) return 401 if no valid token is present. Read operations
(GET, HEAD, PROPFIND) are always allowed.

### Drive Management

`DLFSDriveManager` is the local routing and service layer. `createRouter()` fails
closed for unmounted identities. `mount(identity, drives)` and
`mountAnonymous(drives)` route requests to an owner's `DLFSDrives` component,
whose lattice map is authoritative and whose cached `DLFSDrive` objects are local
views only. One manager may therefore span owners, physical regions and hosted
roots without changing the physical `DLFSRegion` model. `createEphemeral()` is the
explicit test/demo mode; it maintains a process-local
`ConcurrentHashMap<String, FileSystem>` keyed by `"{identity}:{driveName}"`.

| Operation | Method | Semantics |
|-----------|--------|-----------|
| Create | `createDrive(identity, name)` | `putIfAbsent` — fails if exists |
| Get | `getDrive(identity, name)` | Returns `FileSystem` or null |
| Delete | `deleteDrive(identity, name)` | `remove` — fails if not exists |
| List | `listDrives(identity)` | Scans keys by identity prefix |
| Rename | `renameDrive(identity, old, new)` | Atomic under the manager lock |
| Seed | `seedDrive(identity, name, fs)` | Unconditional `put` (for testing) |

Standalone drives use `DLFS.createLocal()`. The hosted constructor accepts
`DLFSDrives`; each drive is then a descended component and cursor into a
`MapLattice<DLFSLattice>`.

### Request Flow

```
HTTP request
  → Javalin routing
    → AuthMiddleware (extract JWT identity)
      → DLFSWebDAV handler
        → parseDrivePath(ctx): extract drive name + file path from URI
        → getIdentity(ctx): read DID from auth context
        → resolveFilePath(ctx, dp): look up drive, resolve path
        → Standard java.nio.file operations (Files.read, Files.write, etc.)
          → DLFSLocal (cursor.updateAndGet for writes)
```

File operations go through the NIO filesystem abstraction. Whole-file replacements
use `DLFileSystem.writeAllBytes` so truncate and write form one filesystem-locked
mutation. Each external transport mutation refreshes the caller-managed timestamp and
calls `sync()`; PUT also supports ETag-based `If-Match` and `If-None-Match`
preconditions. DLFS itself does not advance that timestamp.

### Content-Type Detection

`DLFSWebDAV.guessContentType()` provides basic MIME type detection for common file
extensions (`.txt`, `.html`, `.json`, `.xml`, `.png`, `.jpg`, `.pdf`, `.css`, `.js`).
Unknown extensions default to `application/octet-stream`.

### PROPFIND Responses

`PropfindResponse` generates RFC 4918-compliant XML using StAX (`XMLStreamWriter`).
Two modes:

- **Drive listing**: Returns a `<multistatus>` with `<collection>` entries for each
  drive name.
- **File/directory**: Returns properties including `<getcontentlength>`,
  `<getlastmodified>`, `<resourcetype>` (with `<collection/>` for directories).

Child entries are included when `Depth: 1` is requested.

## Node Format and NIO Timestamps

The CAD028 node vector is:

```text
[entries data metadata utime tombstones? mtime? ...future-fields]
```

| Slot | Meaning |
|------|---------|
| 0 | Directory `Index<String,Node>`, or nil for a file |
| 1 | File Blob, or nil for a directory |
| 2 | Opaque application metadata |
| 3 | Required Long mutation/merge clock (`utime`) |
| 4 | Optional non-empty directory `Index<String,Long>` of deletion times; nil padding if later fields exist |
| 5 | Optional Long content modification time in epoch milliseconds (`mtime`); absent/nil falls back to `utime` |
| 6+ | Unknown extension fields, accepted and preserved |

Exactly one of entries and data is non-null. Legacy four- and five-field nodes
remain readable without rewriting. Local edits materialise `mtime` when needed;
merge does not synthesise an extension. Older readers which reject extra fields
must upgrade before receiving extended nodes. Empty tombstone indexes remain
invalid; an unused slot is omitted or padded with nil.

When effective `mtime` equals `utime`, writers may store nil in the `mtime` slot
or omit it as an optional size optimisation. Truncation is safe only when no
unknown trailing fields exist: retain five fields if tombstones are present,
otherwise remove the unused tombstone padding too and retain four. These forms
have the same timestamp meaning but different encodings and hashes; this is a
writer choice, not automatic normalisation during reading or merging. Before a
later metadata-only edit advances `utime`, the old effective `mtime` must still
be materialised so it remains unchanged.

Content writes and truncation set both times to the supplied operation time.
Metadata edits and renames preserve `mtime` while updating `utime`. Directory
`mtime` tracks immediate entry creation, removal and replacement, not edits to a
descendant's contents; ancestor `utime` still propagates. Setting NIO modification
time changes `mtime` to the requested value and stamps `utime` with operation time.
Merge ordering uses only `utime`. Directory metadata, `mtime` and future extension
fields come from the node selected by that ordering, with own-value preference on
ties; they are not independently merged.

No access, creation or status-change time is stored. NIO reports access time as
`mtime` and creation time as the epoch. Unsupported time setters cause no writes.
`COPY_ATTRIBUTES` and moves preserve `mtime`; ordinary copies use destination
operation time. Recursive DLFS copies share unchanged descendant nodes, including
their timestamps, and stamp only the new top-level entry.

Same-drive moves perform one atomic cursor update, including when paths come from
different component views of that working drive. Components retain the working
root and region/owner/drive path in `DLFSLocation`; forks have distinct identities.
Opaque cursor projections cannot infer aliases: lower-level adapters must retain
and pass the same logical location when constructing views.

Cross-drive moves publish a snapshot at the destination before conditionally
deleting that same source value. If the source changes meanwhile, both versions
remain and the move reports an error. Such moves cannot honour `ATOMIC_MOVE`.
Different NIO providers use Java's generic copy/move path. Copies of directories
are shallow unless `DLFSOption.RECURSIVE` is supplied between DLFS drives.

## Merge Semantics

### Deterministic Merge

`DLFSNode.merge(a, b)` takes two arguments. The merge timestamp is derived
deterministically as `max(getUTime(a), getUTime(b))` — no external timestamp needed.

- **Files**: Newer timestamp wins. Equal timestamps: `a` preferred.
- **Directories**: Live entries merged recursively via `Index.mergeDifferences()`.
- **Metadata**: Position 2 is an opaque, nullable `ACell`. DLFS does not interpret or
  recursively merge it; metadata follows the containing node's timestamp ordering, with
  `a` preferred on a tie. A metadata-only mutation must therefore update the node timestamp.
- **Tombstones**: A directory node keeps deletions in a separate tombstone index
  (deleted child name → deletion timestamp), held as an optional 5th node element.
  An empty slot is omitted, or null padding before extension fields. Live entries and
  tombstones are merged independently, then reconciled: a tombstone whose timestamp is
  newer than a live child deletes it; a newer creation resurrects the name.
- **Directional ties**: equal-timestamp conflicts prefer `a`, the current local (`own`)
  value. This includes unequal files and live-versus-tombstone conflicts. Merge is
  therefore intentionally not fully commutative; callers must preserve own/foreign
  direction. A local mutation replaces equal-timestamp local state and must remain
  `own` when an older snapshot is merged back.
- **Idempotency**: `merge(a, a) == a`.

### Metadata Contract

Node metadata is deliberately application-defined, while following these interoperability
and safety conventions:

- `nil` means no metadata. Any other value must be an immutable CAD value (`ACell`).
- Extensible structured metadata should use a map with stable, namespace-qualified keys;
  consumers must ignore keys they do not understand.
- Metadata readers must validate their own schema and impose suitable size and depth limits
  before processing values received from another replica.
- Core DLFS operations treat metadata as an opaque leaf and do not traverse it merely to
  merge directory contents.
- `DLFSNode.withMetadata(node, metadata, timestamp)` is the standard immutable update helper;
  the supplied timestamp participates in normal node conflict resolution.

Applications that need field-level or custom metadata convergence should place an explicit
lattice value in the metadata slot and perform that merge at their application boundary;
DLFS itself guarantees only whole-slot last-write-wins behaviour.

### Mutation and Merge Timestamps

| Timestamp | Source | Used For |
|-----------|--------|----------|
| **Mutation timestamp** | `DLFSLocal.getContext().currentTimestamp()` | Creating new files, directories, tombstones |
| **Merge timestamp** | `max(getUTime(a), getUTime(b))` | Conflict resolution during merge |

These are deliberately separate. The application supplies mutation time through the
component's `LatticeContext` exposed by the NIO adapter; neither `DLFileSystem` nor `DLFSLocal` updates a
separate clock. DLFS consumes the context timestamp exactly and never derives a later
mutation time from stored nodes or tombstones. The application owns the clock and its
refresh cadence. An empty context uses `LatticeContext.currentTimestamp()`'s standalone
wall-clock fallback.
The merge timestamp is always derived from the data being merged—never from the
current application context.

## API

### Factory Methods

```java
// Standalone drive with its own lattice cursor
DLFSLocal drive = DLFS.create();

// Connected drive — path into a parent lattice cursor, heap-backed file data
AForkableCursor<?> parent = Cursors.root(MapLattice.create(DLFSLattice.INSTANCE));
DLFSLocal drive = DLFS.connect(parent, Strings.create("myDrive"));

// Store-backed cursor drive — large NIO writes persist incrementally
DLFSLocal storedDrive = DLFS.connect(parent, Strings.create("storedDrive"), store);

// Alias for a standalone local drive
DLFSLocal drive = DLFS.createLocal();
```

### Java NIO Filesystem

Standard Java NIO operations work transparently:

```java
DLFSLocal drive = DLFS.create();
drive.getCursor().setContext(
    drive.getCursor().getContext().withTimestamp(operationTimestamp));

Path file = Files.createFile(drive.getPath("readme.txt"));
Files.write(file, "hello".getBytes());
Files.createDirectory(drive.getPath("subdir"));

// Read back
byte[] data = Files.readAllBytes(drive.getPath("readme.txt"));
```

### Fork and Sync

`fork()` creates an isolated working copy backed by a `ForkedCursor` whose merge
function is `DLFSLattice`.
Changes are invisible to the original until `sync()` merges them back via
lattice merge:

```java
DLFSLocal drive = DLFS.create();
Files.createDirectory(drive.getPath("/data"));

// Fork for isolated batch operations
DLFSLocal batch = drive.fork();
Files.writeString(batch.getPath("/data/file1.txt"), "Batch write 1");
Files.writeString(batch.getPath("/data/file2.txt"), "Batch write 2");

// Original doesn't see changes yet
assert !Files.exists(drive.getPath("/data/file1.txt"));

// Sync merges all changes atomically via lattice merge
batch.sync();

// Now visible
assert Files.exists(drive.getPath("/data/file1.txt"));
```

### Replication

```java
DLFSLocal driveA = DLFS.create();
DLFSLocal driveB = DLFS.create();

// Make changes to both
driveA.getCursor().setContext(
    driveA.getCursor().getContext().withTimestamp(timestampA));
Files.createFile(driveA.getPath("fileA.txt"));

driveB.getCursor().setContext(
    driveB.getCursor().getContext().withTimestamp(timestampB));
Files.createFile(driveB.getPath("fileB.txt"));

// Merge B into A — both files present after merge
driveA.replicate(driveB);
```

### Content Addressing

```java
Hash rootHash = drive.getRootHash();           // Content hash of entire tree
AVector<ACell> node = drive.getNode(path);     // Get raw node at path
```

## Lattice Integration

### Cursor-Backed Drives

A `DLFSLocal` is backed by an `AForkableCursor<AVector<ACell>>` which may be a
standalone `Root` or a child cursor navigated from a parent drives map, declared
with `DLFSLattice` as its shape so an absent drive is created correctly. This enables multi-drive setups where each drive is a key in a `MapLattice`:

```java
// Create a lattice of drives
MapLattice<AString, AVector<ACell>> drivesLattice = MapLattice.create(DLFSLattice.INSTANCE);
AForkableCursor<AHashMap<AString, AVector<ACell>>> drivesRoot =
    Cursors.root(drivesLattice);

// Connect a named drive — initialises with zero value if absent and uses the
// containing application's store for incremental blob persistence
DLFSLocal drive = DLFS.connect(drivesRoot, Strings.create("myDrive"), store);

// File operations automatically update the cursor, which propagates
// up through the lattice hierarchy
Files.createFile(drive.getPath("data.txt"));
```

### WebDAV Access

See [WebDAV Layer](#webdav-layer) above for the full HTTP server architecture.
Since WebDAV and the GUI share the same `DLFSLocal` instances (backed by the same
cursors), operations through either path are automatically consistent.

### Hosted Components and Network Sync

`LatticeHost` is the generic store-backed application boundary in `convex-core`.
It may be created directly over a local `AStore`, or exposed by `NodeServer` for a
networked application. A physical `DLFSRegion` can be connected at the root or at
any nested lattice path without depending on NodeServer:

```java
KeyedLattice lattice = KeyedLattice.create(
    Keyword.intern("documents"), DLFSRegion.LATTICE);
NodeServer<Index<Keyword, ACell>> node =
    new NodeServer<>(lattice, store);
node.launch();

DLFSApplication<Index<Keyword, ACell>> app = DLFSApplication.connect(Lattice.ROOT,
    node.getHost(), Keyword.intern("documents"));
DLFSDrives drives = app.drives(ownerKey);
DLFSDrive drive = drives.createDrive("home");
Files.writeString(drive.fileSystem().getPath("/data.txt"), "value");
app.sync();
node.getHost().sync();
```

For a non-networked host, explicitly open `LatticeHost.open(store)`,
set its context, and connect with `DLFSApplication.connect(Lattice.ROOT, root, Keywords.FS)`.
There is no hidden-host factory: retain the root and publish with `root.sync()`.

`DLFSDriveManager.mount(identity, drives)` exposes owner components to WebDAV and
MCP and remains the place for local mappings that span owners, physical regions
or hosted roots. `DLFSServer` owns transport only and never owns the application,
host or store lifecycle. Supply a completion callback when creating the manager
to choose publication after successful mutations, for example
`new DLFSDriveManager(drives, () -> { app.sync(); root.sync(); })`.
The callback belongs to service wiring; domain components know only their cursors.
The component hierarchy exposes its root `AStore` internally to each drive's
`DLFSLocal` adapter, while `sync()` remains the explicit publication and replication
boundary. Streamed blob writes install store-backed references every 16 MiB without
moving the cursor or selecting retained GC roots. A region, drive collection or
drive `fork()` is a temporary working component and only merges logical changes when
explicitly synced.

## Production Readiness

### Current State

| Component | Status |
|-----------|--------|
| DLFSNode (pure merge/navigate) | ✅ Robust core, bounded foreign validation |
| DLFSLattice (lattice semantics) | ✅ Robust core, directional tie semantics |
| DLFSLocal (Java NIO filesystem) | ⚠️ Focused API; unsupported operations fail explicitly |
| Cursor-backed drives | ✅ Working (AForkableCursor) |
| Factory methods (create/connect) | ✅ Implemented |
| Fork/sync batch operations | ✅ Implemented |
| Deterministic merge (no time param) | ✅ Implemented |
| DAV class 1 server | ✅ Working, bounded and loopback by default |
| DLFSBrowser GUI | ✅ Working (lifecycle managed) |
| Standalone multi-drive management | ✅ Working in process (GUI + WebDAV) |
| Cursor-backed drive registry | ✅ Atomic create/rename/delete with explicit sync |
| Hosted DLFS components | ✅ Generic root parent, arbitrary region path, owner drive maps, temporary forks |
| DLFSBrowser with NodeServer | ✅ Etch-backed drive contents and persistent registry |
| Network replication via NodeServer | ⚠️ Basic (pull/push working, no signing) |

### DLFSBrowser

The DLFSBrowser runs a `NodeServer` backed by an Etch store (default
`~/.convex/dlfs/dlfs.db`). The lattice value is a `MapLattice<DLFSLattice>`
mapping drive names to DLFS node trees. Each drive is a `DLFSLocal` backed by a
descended cursor into this map.

A WebDAV server starts alongside, exposing drives over HTTP. Since both GUI and
WebDAV share the same `DLFSLocal` instances (same cursors), operations through
either path are automatically consistent. The status bar shows the store path,
WebDAV URL, and UNC path (Windows).

Drive management: create, delete, and switch drives via the UI. New drives are
cursor-backed and registered in WebDAV automatically.

### Persistence

Component-backed drives obtain the root component's `AStore`; cursor-level embedders
get the same incremental blob persistence by passing that store to
`DLFS.connect(parent, name, store)` or `DLFS.open(parent, name, store)`. Both paths
therefore use `DLFSLocal` and `Cells.persist` rather than separate persistence
adapters. `DLFSDriveManager.sync()` marks the explicit publication boundary.
Persisting immutable blob structure before that boundary replaces direct references
with soft references but does not select a GC root. The filesystem neither owns nor
closes the supplied store.
Mounted manager routes may adapt multiple owners' `DLFSDrives` components.
Standalone drives (via `DLFS.createLocal()`) and the two-argument cursor factories
remain entirely in-memory and need explicit persistence if required.

## CAD045 Conformance

DLFS follows the lattice application best practices defined in CAD045:

| Principle | DLFS Implementation |
|-----------|---------------------|
| **Lattice layer** | `DLFSLattice` extends `ALattice<AVector<ACell>>` with recursive directory merge via `IndexLattice` |
| **Pure functions** | `DLFSNode` — all merge/navigate/update operations are pure, deterministic, and side-effect-free |
| **Cursor-backed mutability** | `DLFSLocal` wraps `AForkableCursor<AVector<ACell>>`; all writes via `cursor.updateAndGet()` |
| **Factory methods** | `DLFS.create()` (standalone), `DLFS.connect(parent, name, store)` (store-backed cursor), component-backed `DLFSDrive.fileSystem()` |
| **Fork/sync** | `DLFileSystem.fork()` → `ForkedCursor` merging with `DLFSLattice` for isolated batch operations; `sync()` merges back |
| **Lattice merge** | `DLFSLocal.merge()` delegates to `rootCursor.merge(DLFSLattice.INSTANCE, other)` |
| **Foreign validation** | `DLFSLattice.checkForeign()` validates node structure (vector length, timestamp type) |
| **OwnerLattice** | ROOT lattice: `:fs → OwnerLattice(MapLattice(DLFSLattice))` — forgery-resistant with `LatticeContext` |
| **NIO compatibility** | Focused basic-file API; unsupported operations throw explicitly |

## Future Enhancements

### Owner-Signed Drives (OwnerLattice Integration)

For multi-user network sync, each owner's DLFS tree should be wrapped in
`OwnerLattice → SignedLattice → DLFSLattice`. This requires:

- OwnerLattice passing `LatticeContext` through to child lattices
- `SignedLattice` using `context.getSigningKey()` to sign merged values
- DLFSLocal `syncToNode()` / `syncFromNode()` convenience API

```
User modifies DLFS (Java NIO API)
    ↓
DLFSLocal (cursor into lattice tree)
    ↓
Sync to NodeServer
    ↓
OwnerLattice.merge(context) → SignedLattice → DLFSLattice
    ↓
Network propagation via LatticePropagator
    ↓
Remote NodeServers receive and merge with signature verification
```

This is not needed for single-user or trusted-network deployments but is required
for multi-user production use with untrusted peers.

### Filtering for Public/Private Drives

Using `LatticeFilter` on propagators, private drives can be excluded from public
broadcast while still being persisted locally. This depends on the filtering
infrastructure in `LatticePropagator` (Phase 5 in PERSISTENCE.md).
