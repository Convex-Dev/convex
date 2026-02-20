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
│  - DLFSDriveManager: per-identity drive registry             │
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
│  - Wraps ALatticeCursor<AVector<ACell>>                      │
│  - Provides Java NIO Path operations                         │
│  - fork() / sync() for batch operations                      │
│  - replicate(other): convenience method                      │
└──────────────────────┬───────────────────────────────────────┘
                       │
                       │ Uses cursor.updateAndGet() / merge()
                       │
┌──────────────────────▼───────────────────────────────────────┐
│       ALatticeCursor<AVector<ACell>> Lattice Cursor           │
│        (Atomic mutable reference with lattice merge)         │
│  - RootLatticeCursor: standalone drive                        │
│  - DescendedCursor: path into larger lattice                  │
│  - ForkedLatticeCursor: isolated fork for batch ops           │
│  - merge(other): lattice merge (always succeeds)             │
│  - fork() / sync(): isolated working copies                  │
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
with pure DLFSNode functions. Merges use `cursor.merge()` with lattice semantics.
The cursor can be a `RootLatticeCursor` (standalone drive), a `DescendedCursor`
(path into a larger lattice tree), or a `ForkedLatticeCursor` (isolated working copy).

### Key Files

| File | Location | Purpose |
|------|----------|---------|
| `DLFSNode` | `convex-core/.../lattice/fs/DLFSNode.java` | Pure merge/navigate/update functions |
| `DLFSLattice` | `convex-core/.../lattice/fs/DLFSLattice.java` | ALattice implementation |
| `DLFSLocal` | `convex-core/.../lattice/fs/impl/DLFSLocal.java` | Mutable filesystem (Java NIO) |
| `DLFileSystem` | `convex-core/.../lattice/fs/DLFileSystem.java` | Abstract base with timestamp |
| `DLFS` | `convex-core/.../lattice/fs/DLFS.java` | Factory methods |
| `DLFSServer` | `convex-dlfs/.../dlfs/DLFSServer.java` | Javalin HTTP server lifecycle |
| `DLFSWebDAV` | `convex-dlfs/.../dlfs/DLFSWebDAV.java` | WebDAV route handlers |
| `DLFSDriveManager` | `convex-dlfs/.../dlfs/DLFSDriveManager.java` | Per-identity drive registry |
| `PropfindResponse` | `convex-dlfs/.../dlfs/PropfindResponse.java` | RFC 4918 XML response builder |

## WebDAV Layer

The `convex-dlfs` module provides a WebDAV-compatible HTTP server that exposes DLFS
drives to standard clients — OS file managers, `curl`, WebDAV libraries (Sardine),
and any HTTP client.

### Server Stack

```
DLFSServer
  ├── Javalin (HTTP framework, virtual threads via Jetty)
  ├── AuthMiddleware (Ed25519 JWT bearer tokens)
  └── DLFSWebDAV (route handlers)
        └── DLFSDriveManager (per-identity drive registry)
              └── DLFSLocal instances (one per identity + drive name)
```

`DLFSServer` owns the Javalin lifecycle. It configures CORS, wires authentication
middleware (optional), registers WebDAV routes, and configures Jetty with minimal
platform threads (1 acceptor + 1 selector) since request handling uses virtual threads.

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
| `LOCK`/`UNLOCK` | — | Stub responses for client compatibility |
| `PROPPATCH` | — | Stub 200 response |

LOCK, UNLOCK and PROPPATCH return minimal valid responses to satisfy WebDAV clients
(e.g. Windows Explorer, macOS Finder) that probe for these methods. Actual locking
is not implemented — DLFS uses lattice merge for conflict resolution, not locks.

### Authentication

Authentication is optional and controlled by the `AKeyPair` passed to
`DLFSServer.create()`. When a key pair is provided:

1. `AuthMiddleware` extracts Ed25519 JWT bearer tokens from the `Authorization` header
2. The token's `sub` claim provides the user identity as a DID string (`did:key:...`)
3. `DLFSWebDAV.getIdentity()` reads this from the request context
4. `DLFSDriveManager` uses the identity to namespace drives per user

When `requireAuthForWrites` is enabled on `DLFSWebDAV`, mutating operations (PUT,
DELETE, MKCOL, MOVE, COPY) return 401 if no valid token is present. Read operations
(GET, HEAD, PROPFIND) are always allowed.

### Drive Management

`DLFSDriveManager` maintains a `ConcurrentHashMap<String, FileSystem>` keyed by
`"{identity}:{driveName}"` (anonymous identity uses `":{driveName}"`).

| Operation | Method | Semantics |
|-----------|--------|-----------|
| Create | `createDrive(identity, name)` | `putIfAbsent` — fails if exists |
| Get | `getDrive(identity, name)` | Returns `FileSystem` or null |
| Delete | `deleteDrive(identity, name)` | `remove` — fails if not exists |
| List | `listDrives(identity)` | Scans keys by identity prefix |
| Rename | `renameDrive(identity, old, new)` | Atomic `putIfAbsent` + `remove` |
| Seed | `seedDrive(identity, name, fs)` | Unconditional `put` (for testing) |

Currently drives are in-memory (`DLFS.createLocal()`). The drive manager is designed
to be replaceable with a lattice cursor-backed implementation where each drive is a
descended cursor into a `MapLattice<DLFSLattice>`.

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

All file operations go through the standard `java.nio.file` API. The WebDAV layer
has no direct knowledge of lattice cursors, nodes, or merge semantics — it operates
entirely through the NIO `FileSystem` abstraction.

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

## Merge Semantics

### Deterministic Merge

`DLFSNode.merge(a, b)` takes two arguments. The merge timestamp is derived
deterministically as `max(getUTime(a), getUTime(b))` — no external timestamp needed.

- **Files**: Newer timestamp wins. Equal timestamps: `a` preferred.
- **Directories**: Entries merged recursively via `Index.mergeDifferences()`.
- **Tombstones**: A tombstone with a newer timestamp than a file deletes it.
- **Commutativity**: `merge(a, b) == merge(b, a)` (same inputs → same output).
- **Idempotency**: `merge(a, a) == a`.

### Two Timestamps

| Timestamp | Source | Used For |
|-----------|--------|----------|
| **Drive timestamp** | `DLFileSystem.getTimestamp()` | Creating new files, directories, tombstones |
| **Merge timestamp** | `max(getUTime(a), getUTime(b))` | Conflict resolution during merge |

These are deliberately separate. The drive timestamp is set by the application
(`setTimestamp()` or `updateTimestamp()`). The merge timestamp is always derived from
the data being merged — never from the drive's current time.

## API

### Factory Methods

```java
// Standalone drive with its own lattice cursor
DLFSLocal drive = DLFS.create();

// Connected drive — path into a parent lattice cursor
ALatticeCursor<?> parent = Cursors.createLattice(MapLattice.create(DLFSLattice.INSTANCE));
DLFSLocal drive = DLFS.connect(parent, Strings.create("myDrive"));

// Legacy (delegates to create())
DLFileSystem drive = DLFS.createLocal();
```

### Java NIO Filesystem

Standard Java NIO operations work transparently:

```java
DLFSLocal drive = DLFS.create();
drive.updateTimestamp();

Path file = Files.createFile(drive.getPath("readme.txt"));
Files.write(file, "hello".getBytes());
Files.createDirectory(drive.getPath("subdir"));

// Read back
byte[] data = Files.readAllBytes(drive.getPath("readme.txt"));
```

### Fork and Sync

`fork()` creates an isolated working copy backed by a `ForkedLatticeCursor`.
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
driveA.updateTimestamp();
Files.createFile(driveA.getPath("fileA.txt"));

driveB.updateTimestamp();
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

A `DLFSLocal` is backed by an `ALatticeCursor<AVector<ACell>>` which may be a
standalone `RootLatticeCursor` or a `DescendedCursor` navigated from a parent
lattice. This enables multi-drive setups where each drive is a key in a `MapLattice`:

```java
// Create a lattice of drives
MapLattice<AString, AVector<ACell>> drivesLattice = MapLattice.create(DLFSLattice.INSTANCE);
ALatticeCursor<AHashMap<AString, AVector<ACell>>> drivesRoot =
    Cursors.createLattice(drivesLattice);

// Connect a named drive — initialises with zero value if absent
DLFSLocal drive = DLFS.connect(drivesRoot, Strings.create("myDrive"));

// File operations automatically update the cursor, which propagates
// up through the lattice hierarchy
Files.createFile(drive.getPath("data.txt"));
```

### WebDAV Access

See [WebDAV Layer](#webdav-layer) above for the full HTTP server architecture.
Since WebDAV and the GUI share the same `DLFSLocal` instances (backed by the same
cursors), operations through either path are automatically consistent.

### Network Sync via NodeServer

A `NodeServer` can host the drives lattice and propagate changes to peers:

```java
NodeServer<V> node = new NodeServer<>(drivesLattice, store, port);
node.launch();

// Drives backed by cursors into the NodeServer's lattice
// Changes propagate to peers via LatticePropagator
node.sync();
```

## Production Readiness

### Current State

| Component | Status |
|-----------|--------|
| DLFSNode (pure merge/navigate) | ✅ Production ready |
| DLFSLattice (lattice semantics) | ✅ Production ready |
| DLFSLocal (Java NIO filesystem) | ✅ Production ready |
| Lattice cursor-backed drives | ✅ Working (ALatticeCursor) |
| Factory methods (create/connect) | ✅ Implemented |
| Fork/sync batch operations | ✅ Implemented |
| Deterministic merge (no time param) | ✅ Implemented |
| WebDAV server | ✅ Working |
| DLFSBrowser GUI | ✅ Working (lifecycle managed) |
| Multi-drive management | ✅ Working (GUI + WebDAV) |
| DLFSBrowser with NodeServer | ✅ Etch-backed persistence, WebDAV, drive management |
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

Cursor-backed drives persist through the NodeServer's `LatticePropagator`
(EtchStore). Standalone drives (via `DLFS.createLocal()`) are in-memory only
and need explicit persistence if required.

## CAD045 Conformance

DLFS follows the lattice application best practices defined in CAD045:

| Principle | DLFS Implementation |
|-----------|---------------------|
| **Lattice layer** | `DLFSLattice` extends `ALattice<AVector<ACell>>` with recursive directory merge via `IndexLattice` |
| **Pure functions** | `DLFSNode` — all merge/navigate/update operations are pure, deterministic, and side-effect-free |
| **Cursor-backed mutability** | `DLFSLocal` wraps `ALatticeCursor<AVector<ACell>>`; all writes via `cursor.updateAndGet()` |
| **Factory methods** | `DLFS.create()` (standalone), `DLFS.connect(parent, name)` (node-attached) |
| **Fork/sync** | `DLFileSystem.fork()` → `ForkedLatticeCursor` for isolated batch operations; `sync()` merges back |
| **Lattice merge** | `DLFSLocal.merge()` delegates to `rootCursor.merge()` using lattice semantics |
| **Foreign validation** | `DLFSLattice.checkForeign()` validates node structure (vector length, timestamp type) |
| **OwnerLattice** | ROOT lattice: `:fs → OwnerLattice(MapLattice(DLFSLattice))` — forgery-resistant with `LatticeContext` |
| **NIO compatibility** | Full Java NIO FileSystem API; transparent to application code |

## Future Enhancements

### Context-Aware Merge API

`DLFSLattice.merge(context, own, other)` accepts a `LatticeContext` but currently
ignores the context timestamp (merge is fully deterministic from node data). A future
enhancement could use context for:

- Explicit timestamp override for advanced use cases
- Signing key propagation for authenticated merges

A corresponding `mergeWithContext(other, context)` method on `DLFSLocal` would
expose this to applications.

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
