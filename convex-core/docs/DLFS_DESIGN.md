# DLFS Design

Decentralised Lattice File System — a content-addressed, CRDT-mergeable filesystem
built on Convex immutable data structures with Java NIO compatibility.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    User Application                          │
│              (Java NIO FileSystem API)                       │
└──────────────────────┬───────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────┐
│                  DLFileSystem / DLFSLocal                     │
│           (Mutable Java interface to DLFS)                   │
│  - Wraps ACursor<AVector<ACell>>                             │
│  - Provides Java NIO Path operations                         │
│  - Drive timestamp: for NEW file operations                  │
│  - replicate(other): convenience method                      │
└──────────────────────┬───────────────────────────────────────┘
                       │
                       │ Uses cursor.updateAndGet()
                       │
┌──────────────────────▼───────────────────────────────────────┐
│           ACursor<AVector<ACell>> Cursor                      │
│        (Atomic mutable reference — or lattice cursor)        │
│  - Root<V>: simple atomic reference                          │
│  - DescendedCursor<V>: path into larger lattice       │
│  - updateAndGet(rootNode -> ...)                             │
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

**DLFSLocal** — Mutable Java NIO filesystem backed by a cursor. All file operations
(`createFile`, `write`, `delete`, `createDirectory`) go through `cursor.updateAndGet()`
with pure DLFSNode functions. The cursor can be a simple `Root<V>` (standalone drive)
or a `DescendedCursor<V>` (path into a larger lattice tree).

### Key Files

| File | Location | Purpose |
|------|----------|---------|
| `DLFSNode` | `convex-core/.../lattice/fs/DLFSNode.java` | Pure merge/navigate/update functions |
| `DLFSLattice` | `convex-core/.../lattice/fs/DLFSLattice.java` | ALattice implementation |
| `DLFSLocal` | `convex-core/.../lattice/fs/impl/DLFSLocal.java` | Mutable filesystem (Java NIO) |
| `DLFileSystem` | `convex-core/.../lattice/fs/DLFileSystem.java` | Abstract base with timestamp |
| `DLFS` | `convex-core/.../lattice/fs/DLFS.java` | Factory methods |

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

### Java NIO Filesystem

Standard Java NIO operations work transparently:

```java
DLFileSystem drive = DLFS.createLocal();
drive.updateTimestamp();

Path file = Files.createFile(drive.getPath("readme.txt"));
Files.write(file, "hello".getBytes());
Files.createDirectory(drive.getPath("subdir"));

// Read back
byte[] data = Files.readAllBytes(drive.getPath("readme.txt"));
```

### Cursor-Based Mutability

All operations are atomic via the underlying cursor:

```java
// Simple standalone drive
DLFileSystem drive = DLFS.createLocal();

// Cursor-backed drive (path into larger lattice)
ACursor<AVector<ACell>> cursor = rootCursor.path(Strings.create("myDrive"));
DLFSLocal drive = new DLFSLocal(DLFS.provider(), "myDrive", cursor);
```

### Replication

```java
DLFileSystem driveA = DLFS.createLocal();
DLFileSystem driveB = DLFS.createLocal();

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

A `DLFSLocal` can be backed by any `ACursor<AVector<ACell>>`, including a
`DescendedCursor` that represents a path into a larger lattice tree.
This enables multi-drive setups where each drive is a key in a `MapLattice`:

```java
// Create a lattice of drives
MapLattice<AVector<ACell>> drivesLattice = MapLattice.create(DLFSLattice.INSTANCE);
RootLatticeCursor<AHashMap<AString, AVector<ACell>>> drivesRoot =
    Cursors.createLattice(drivesLattice);

// Descend to a specific drive
ACursor<AVector<ACell>> driveCursor = drivesRoot.path(Strings.create("myDrive"));
DLFSLocal drive = new DLFSLocal(DLFS.provider(), "myDrive", driveCursor);

// File operations automatically update the cursor, which propagates
// up through the lattice hierarchy
Files.createFile(drive.getPath("data.txt"));
```

### WebDAV Server

The `convex-dlfs` module provides `DLFSServer` / `DLFSWebDAV` for HTTP access to
DLFS drives. Since WebDAV and the GUI share the same `DLFSLocal` instances (backed
by the same cursors), operations through either path are automatically consistent.

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
| Cursor-backed drives | ✅ Working |
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
