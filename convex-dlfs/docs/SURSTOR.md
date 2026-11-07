# Design notes: SurStor, a self-sovereign Dropbox on DLFS

> **Status:** early-stage discussion notes, written with collaborators. Not an agreed plan, and
> nothing here has been built.

## Context

DLFS (Data Lattice File System) already provides content-addressed, CRDT-mergeable file storage with WebDAV, Java NIO, P2P lattice replication, Etch persistence, and Ed25519 JWT auth. What's missing for a "self-sovereign Dropbox" is a **web UI** and a **browser-friendly REST API**. SurStor wraps DLFS with these layers.

## Architecture

Single Javalin server process, three route groups:

```
Browser (vanilla JS SPA)
    │
    ▼
Javalin HTTP Server (single port)
    ├── /                    Static web UI (classpath resources)
    ├── /surstor/api/v1/*    SurStor REST API (JSON)
    └── /dlfs/*              WebDAV API (existing, unchanged)
    │
    ▼
DLFSDriveManager + DLFSLocal (java.nio.file)
    │
    ▼
Lattice Cursors → NodeServer → Etch + P2P replication
```

## Location

**Extend `convex-dlfs`** — all dependencies already in scope, no new POM needed.

- Java: `convex.dlfs.surstor` sub-package
- Web: `src/main/resources/convex/dlfs/surstor/pub/`
- Tests: `convex.dlfs.surstor.test`

## New Files

### Java (`convex-dlfs/src/main/java/convex/dlfs/surstor/`)

| File | Purpose |
|------|---------|
| `SurStorServer.java` | Main entry point. Creates NodeServer + Etch store, wires DLFSWebDAV + SurStorAPI + static files into one Javalin app |
| `SurStorAPI.java` | REST endpoint handlers for `/surstor/api/v1/*` |
| `ShareManager.java` | In-memory share grant tracking (`ConcurrentHashMap`) |
| `ShareGrant.java` | Record: id, ownerDID, granteeDID, driveName, createdAt |

### Web UI (`convex-dlfs/src/main/resources/convex/dlfs/surstor/pub/`)

| File | Purpose |
|------|---------|
| `index.html` | SPA shell — nav, drive sidebar, file browser table |
| `css/pico.min.css` | Pico CSS (classless styling, already used in `convex-restapi`) |
| `css/surstor.css` | Minimal custom overrides |
| `js/surstor.js` | Main app: routing, file browser, drive management, sharing UI |
| `js/crypto.js` | Ed25519 keygen (Web Crypto API), JWT signing, DID computation, key export/import |

### Tests (`convex-dlfs/src/test/java/convex/dlfs/surstor/test/`)

| File | Purpose |
|------|---------|
| `SurStorServerTest.java` | Start server, create drive, upload/list/download/delete |

## REST API

All endpoints at `/surstor/api/v1/`, returning JSON. Auth via `Authorization: Bearer <JWT>`.

```
Identity
  GET  /identity                          → { did, publicKey }

Drives
  GET  /drives                            → [ { name, rootHash } ]
  POST /drives          { name }          → 201
  DELETE /drives/{name}                   → 204
  PUT  /drives/{name}   { name: newName } → 200

Files
  GET  /drives/{d}/ls?path=/docs          → [ { name, type, size, modified } ]
  POST /drives/{d}/mkdir?path=/new        → 201
  DELETE /drives/{d}/file?path=/old.txt   → 204
  POST /drives/{d}/rename { from, to }    → 200
  GET  /drives/{d}/download?path=/f.txt   → raw bytes
  POST /drives/{d}/upload?path=/docs/     → multipart upload → 201

Shares
  POST /shares { drive, grantee(DID) }    → 201 + share object
  GET  /shares                            → [ share grants ]
  DELETE /shares/{id}                     → 204

Peers
  GET  /peers                             → [ { address, status } ]
  POST /peers  { address }                → 201
```

## Self-Sovereign Identity

**Client-side Ed25519 via Web Crypto API** — no server-side accounts.

1. First visit → generate Ed25519 keypair → store in `localStorage`
2. Derive DID: `did:key:z6Mk...` (multicodec `0xed01` + pubkey, base58btc)
3. Sign self-issued JWT per request: `{ sub: DID, aud: serverDID, iat, exp }`
4. Existing `AuthMiddleware` + `PeerAuth.verifySelfIssued()` validates — **zero server auth changes**
5. Settings panel: view DID, export seed (hex), import seed to restore on another device

## Sharing Model (Prototype)

Simplest viable: **whole-drive sharing by seeding into grantee's DriveManager namespace**.

1. Owner calls `POST /shares` with grantee's DID
2. Server records `ShareGrant` and calls `driveManager.seedDrive(granteeDID, "shared:" + ownerDID + ":" + drive, ownerFS)`
3. Grantee sees shared drives in their drive list (prefixed `shared:`)
4. Write protection via `requireAuthForWrites` on shared drives

Limitation: shares are in-memory only (lost on restart). Path-level sharing deferred.

## SurStorServer Startup Sequence

Follows the pattern from `DlfsStart.java` and `DLFSBrowser.java`:

1. Generate or load Ed25519 keypair
2. Create `EtchStore` (persistent) or `MemoryStore` (dev mode)
3. Create `NodeServer` with `Lattice.ROOT` + `LatticeContext(keypair)`
4. Navigate cursor to `:fs → accountKey → :value`
5. Create `DLFSDriveManager`, connect default "home" drive via `DLFS.connect()`
6. Create `ShareManager`
7. Build Javalin app:
   - `config.staticFiles.add("/convex/dlfs/surstor/pub", Location.CLASSPATH)`
   - `DLFSWebDAV.addRoutes(app)` (existing)
   - `SurStorAPI.addRoutes(app)` (new)
8. Start on configured port
9. Optionally connect to peers

## Build & Run

```bash
# Build, from the repository root
./mvnw -B install -pl convex-dlfs -am -DskipTests

# Run
java -cp convex-dlfs/target/classes:convex-dlfs/target/dependency/* \
  convex.dlfs.surstor.SurStorServer --port 8080

# Then open http://localhost:8080/
```

## Key Existing Files to Reuse

| File | What to reuse |
|------|---------------|
| `convex-dlfs/.../DLFSServer.java` | Pattern for Javalin + WebDAV setup |
| `convex-dlfs/.../DLFSDriveManager.java` | Drive CRUD, identity isolation |
| `convex-dlfs/.../DLFSWebDAV.java` | WebDAV route registration |
| `convex-cli/.../DlfsStart.java` | NodeServer + Etch + cursor wiring |
| `convex-gui/.../DLFSBrowser.java` | Drive lifecycle, WebDAV server integration |
| `convex-restapi/.../RESTServer.java` | Static file serving pattern |
| `convex-restapi/.../AuthMiddleware.java` | JWT validation (reuse as-is) |

## Web UI Layout

```
┌─────────────────────────────────────────────────┐
│ SurStor    [did:key:z6Mk...abc]    ⚙ Settings  │
├──────────┬──────────────────────────────────────┤
│ Drives   │ home / docs /                        │
│          │ [Upload] [New Folder]                 │
│ ● home   │                                      │
│ ○ photos │  Name          Size    Modified      │
│ ○ work   │  📁 images     —      2026-03-26     │
│          │  📄 readme.txt 1.2KB  2026-03-26     │
│ [+ New]  │  📄 notes.md   340B   2026-03-25     │
│          │                                      │
│ Shared   │                                      │
│ ○ alice: │                                      │
│   photos │                                      │
├──────────┴──────────────────────────────────────┤
│ Peers: peer1.example.com:19888 ✓  [+ Connect]  │
└─────────────────────────────────────────────────┘
```

## Verification

1. Start server, open browser → web UI loads
2. Identity auto-generated, DID shown in nav
3. Create a drive → appears in sidebar
4. Upload a file → appears in file list
5. Download the file → correct content
6. Create folder, rename, delete → all work
7. Export seed, open incognito, import seed → same identity, same drives
8. Connect two instances as peers → files replicate
9. Share drive with second identity → appears in their "Shared" section

## Out of Scope (Prototype)

- Encryption at rest
- File versioning / history UI
- Path-level sharing (only whole-drive)
- Persistent share grants (in-memory only)
- Mobile / desktop sync client
- Quotas / billing
- Full-text search
