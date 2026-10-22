# Convex DLFS

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-dlfs.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-dlfs/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-dlfs)

The **Data Lattice File System (DLFS)** — a decentralised, content-addressed,
lattice-mergeable filesystem built on Convex immutable data structures, with a focused
Java NIO implementation and a DAV class 1 server for access from ordinary file clients.

## Overview

DLFS stores a whole filesystem as an immutable Convex lattice value. Because the
underlying data structures support recursive lattice merges, independent replicas can
be reconciled using timestamp and tombstone rules. Equal-timestamp conflicts are
directional: the current local (`own`) value wins. Applications own the mutation clock
and must supply a sound timestamp policy; DLFS never advances a timestamp from observed
state. Reusing a timestamp is valid for local updates, while operations which must be
ordered across replicas require caller-supplied timestamps expressing that order.

It exposes the filesystem in three ways:

- **Java NIO `FileSystem` API** — use DLFS through standard `java.nio.file.Path` /
  `Files` operations.
- **WebDAV server** — mount or browse drives from curl, an OS file manager, or any
  WebDAV client. Drives appear as top-level directories under `/dlfs/{drive}/{path}`.
- **MCP tools** — drive operations are exposed as Model Context Protocol tools for use
  by AI agents.

## Features

- Content-addressed, immutable storage with CRDT-style `fork()` / `sync()` / `merge()`
- Java NIO `FileSystem` provider for the implemented basic file operations
- DAV class 1 server built on Javalin, with virtual-thread request handling
- Per-identity, multi-drive registry — each authenticated user gets their own drives
- Optional Ed25519 JWT bearer-token authentication
- Model Context Protocol (MCP) integration

## Installation

### Maven

```xml
<dependency>
    <groupId>world.convex</groupId>
    <artifactId>convex-dlfs</artifactId>
    <version>0.8.17</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-dlfs:0.8.17'
```

## Usage

### Run a WebDAV server

The module has a standalone entry point which composes the full NodeServer-hosted
lattice application stack without the Convex CLI:

```bash
java -cp convex.jar convex.dlfs.Main \
  --config dlfs-config.json5
```

`DLFSConfig` parses JSON5 directly into an immutable Convex `AMap` and provides typed
views over four sections: the generic lattice `node`, `dlfs` application policy,
the local `http` transport and explicit `security` decisions. See the packaged
[`config-example.json5`](src/main/resources/convex/dlfs/config-example.json5) for a
commented example. `CONVEX_DLFS_CONFIG` may supply the file path; the old port,
drive and inbound switches remain useful as development overrides.

```json5
{
  node: {
    port: 19888,
    maxFutureTimestampSkew: 30000,
    store: "dlfs.etch",
    etch: {version: 3},
    keypair: "<32-byte Ed25519 seed hex>",
    url: "tcp://dlfs.example.org:19888",
    bootstrap: [
      {key: "<remote AccountKey hex>", url: "tcp://seed.example.org:19888"},
    ],
  },
  dlfs: {
    path: ["fs"],
    drive: "home",
    mounts: [
      {identity: null, owner: "node"},
      {identity: "node", owner: "node"},
      // {identity: "did:key:z...", owner: "<replicated AccountKey hex>"},
    ],
  },
  http: {bind: "127.0.0.1", port: 8080, maxRequestBytes: 67108864},
  security: {
    latticeInbound: "deny",
    writeAuthentication: "node-key",
  },
}
```

`node.maxFutureTimestampSkew` is the maximum accepted lead, in milliseconds, for
timestamp-ordered DLFS updates and tombstones received from another replica. It
defaults to 30 seconds; lower it only when participating hosts have tighter clock
synchronisation.

Bootstrap entries pair a TCP address with its expected `AccountKey`; the connection
manager challenges that identity after connecting and keeps an unverified connection
under the conservative message-size cap. Verification failure is not yet a fatal
transport error, so this is identity-aware bootstrap rather than a private trust
boundary. Bootstrap contacts are not a separate DLFS concept: the same `NodeServer`
connection and lattice propagation path carries `:p2p` discovery data and DLFS state.
Bootstrap configures the outbound side only; the remote node must separately grant an
inbound lattice view.

The default configuration remains a zero-setup, loopback-only example with a
temporary Etch store and an ephemeral node key. Configure both `node.store` and
`node.keypair` before relying on identity or data across restarts. The key seed makes
the config file a secret. An encrypted `node.etch` policy therefore requires a
persistent key and records its public key as the store hint.

Inbound lattice access defaults to `deny`. Setting `latticeInbound: "public"`
exposes the complete primary lattice view to every inbound connection; it is not a
private-peer allowlist. HTTP binds to `127.0.0.1` by default. A non-loopback listener
with `writeAuthentication: "none"` is rejected unless
`allowUnauthenticatedHTTP: true` explicitly acknowledges the risk. `"node-key"`
validates bearer tokens for mutations using the stable node identity, while
`"deny"` makes the service read-only.

For a current two-node local replication setup, give each process a stable and distinct
key, store, port and `tcp://127.0.0.1:...` URL, set `node.allowPrivateURL: true`, list the
other node under `node.bootstrap`, use the same `dlfs.path`, and explicitly set
`latticeInbound: "public"` on both. This is suitable for a controlled local network,
not an Internet-facing private deployment. A replicated remote owner remains hidden
until `dlfs.mounts` maps a local HTTP identity to that owner's key.

The NodeServer hosts a registered DLFS application at `:fs`, so root
publication follows the real persistence and replication path. It accepts only the
configured DLFS region; compose additional applications to serve other regions. `dlfs.path` may place
the region under a custom, otherwise unused top-level region, for example
`["applications", "documents"]`. `dlfs.mounts` is deliberately local service policy:
it maps anonymous, the symbolic local `"node"` DID, or an explicit `did:key` identity
to the local owner (`owner: "node"`) or any replicated owner `AccountKey`. Replication
never makes another user's drives automatically visible over HTTP.

The equivalent programmatic setup is:

```java
EtchStore store = EtchStore.createTemp("convex-dlfs");
AKeyPair keyPair = AKeyPair.generate();
var host = LatticeHost.open(store);
var app = host.addApplication(new DLFSApplication(host, keyPair::signData));
var node = new NodeServer<>(host);
node.launch();
DLFSDriveManager drives = new DLFSDriveManager(
    app.drives(keyPair.getAccountKey()), () -> { app.sync(); host.sync(); });
drives.createDrive(null, "home");
app.sync();

DLFSServer server = DLFSServer.create(drives);
server.start(8080); // use 0 for a random port

// Drives are now served under http://localhost:8080/dlfs/{drive}/{path}
```

The server binds to `127.0.0.1` by default. Calling `setBindHost(...)` is an explicit
decision to expose it on another interface. `createEphemeralWithAudience(keyPair)`
adds JWT validation and requires authentication for mutations. Production code
normally supplies an explicitly configured `DLFSDriveManager` to `create(...)` or
`createWithAudience(...)` instead of using detached ephemeral storage.

DLFS is currently a robust-core project rather than a finished storage product. In
particular, the standalone drive registry is process-local, directory MOVE/COPY and
DAV locking are not implemented, and region-selective peer choice, immediate bootstrap
synchronisation and mutually authenticated private lattice views still need stronger
orchestration. Operators remain responsible for backup and trusted replication policy.

The `convex dlfs start --etch <file>` command uses a cursor-backed registry and requires
a stable keystore key (`--key` or `--public-key`). This prevents a restart from silently
selecting a different owner. Registry mutations and file writes are persisted
synchronously at the service `sync()` boundary.

### Host DLFS in a lattice application

`DLFSApplication` extends the general `ALatticeApplication` component over the
complete hosted lattice root. It composes a physical, multi-owner `DLFSRegion`
at a path of region keys, with no separate root lattice definition. It extends
`Regions`, so applications can register other components alongside DLFS. The stack accepts a generic `LatticeHost` and has no
dependency on `NodeServer`.

```java
// Local: restore or create the standard :fs region in any AStore
var root = LatticeHost.open(store);
var app = root.addApplication(new DLFSApplication(root, myKeyPair::signData));
DLFSDrives myDrives = app.drives(myKeyPair.getAccountKey());
DLFSDrive drive = myDrives.getOrCreateDrive("home");
if (drive == null) throw new IllegalStateException("Cannot open home drive");
FileSystem home = drive.fileSystem();
Files.writeString(home.getPath("/hello.txt"), "Hello");
app.sync();   // complete this application's preparation/publication
root.sync();  // persist and publish the host root
store.flush(); // separate physical durability barrier

// Optional HTTP access uses a manager for identity routing
DLFSServer server = DLFSServer.create(new DLFSDriveManager(myDrives, () -> {
    app.sync();
    root.sync();
}));
server.start(0);
```

`getOrCreateDrive(name, maximumDrives)` enforces the live-drive limit atomically
within shared working state. Opening an existing drive preserves its contents and
timestamps, even when the limit is reached. Invalid names, malformed entries and
failed creation return null. Concurrent deletion can also make a drive unavailable;
the operation does not retry creation after deletion. Independent replicas enforce
their own limits. Use `drive(name)` for lookup only and `createDrive(name)` when an
existing drive should make creation fail. These operations do not publish the root.

Networked bootstrap supplies the host; the DLFS component API is unchanged:

```java
NodeConfig nodeConfig = NodeConfig.port(0);
var host = RelayHost.openHost(Index.<Keyword, ACell>none(), store, nodeConfig);
var networkedApp = host.addApplication(new DLFSApplication(host, myKeyPair::signData,
    Utils::getCurrentTimestampCell, nodeConfig.getMaxFutureTimestampSkew(),
    Keyword.intern("documents")));
var node = new NodeServer<>(host, nodeConfig);
LatticePropagator group = new LatticePropagator(store, LatticePropagatorConfig.create());
group.setTransportKeyPair(myKeyPair);
node.addPropagator(group);
LatticeListener transport = new LatticeListener(nodeConfig);
transport.registerPropagator(group);
transport.setSelector(connection -> group);
node.launch();
transport.launch();

DLFSDrives ownerDrives = networkedApp.drives(myKeyPair.getAccountKey());
DLFSDriveManager routes = DLFSDriveManager.createRouter(() -> {
    networkedApp.sync();
    node.getHost().sync();
})
    .mountAnonymous(ownerDrives)
    .mount(DID.forKey(myKeyPair.getAccountKey()).toString(), ownerDrives);
if (ownerDrives.getOrCreateDrive("home") == null) {
    throw new IllegalStateException("Cannot open home drive");
}
networkedApp.sync();
node.getHost().sync();

DLFSServer networkedServer = DLFSServer.createWithAudience(routes, myKeyPair);
networkedServer.start(0);
```

The standard assembly is `LatticeHost` → `DLFSApplication` → `DLFSRegion`
→ `DLFSDrives` → `DLFSDrive`. The application is not owner-scoped: it can obtain
components for multiple owners. Local identity-to-owner and cross-region mappings
remain `DLFSDriveManager` routing policy; the HTTP server owns no lattice or store
lifecycle.

A custom application can embed `DLFSRegion` at any correctly bound cursor.
For a root application that stages files, explicitly supply a fork:

```java
var files = DLFSRegion.connect(host.cursor().path(Keywords.FS), store,
    myKeyPair::signData, Utils::getCurrentTimestampCell);
var app = host.addApplication(new DLFSApplication(host, Keywords.FS, files.fork()));
```

`app.dlfs()` is the exact registered component. Its incoming merges and local
edits use the same working state, and `app.sync()` publishes that region only.
Multiple applications may explicitly register the same component. The default
`new DLFSApplication(host, myKeyPair::signData)` instead uses a live region, so edits
are immediately part of the host's working state. Without a signer,
`new DLFSApplication(host)` is a relay: it can accept existing signed envelopes,
but cannot write or author a combination that requires a new signature.

Ownership, signing and clocks are explicit DLFS policies; a host or application
`LatticeContext` does not grant signing authority. The standard owner key is an
`AccountKey`. For indirect identities, the full `DLFSRegion.connect` overload takes
an owner/key verifier and an owner-aware signer, alongside the clock and allowed
future timestamp skew. A signer may return null for owners it cannot author.
Every signature it supplies must pass the same owner rule as incoming delivery.
Forks and transaction views retain these policies. A mutable key resolver can
support rotation without reconstructing the component tree; callbacks must be
safe for concurrent use and retries during atomic updates.

A directly connected region is a lightweight view. Syncing its fork updates
only its destination subtree. Host persistence remains a separate operation.
Pass `null` for the store when incremental blob checkpointing is unnecessary.

The application does not own or close its root or store. Owner components are cheap
views and are not retained globally; long-lived services keep the `DLFSDrives`
components they route, which also retains their cached NIO views. Isolated work uses
`app.drives(owner).fork()` or `app.drives(owner).drive(name).fork()` and explicitly
syncs the temporary component when its changes should merge back.

Large channel writes checkpoint blob data through the host `AStore` every 16 MiB.
The component hierarchy supplies that store to the same `DLFSLocal` implementation
used by lower-level cursor embedders. This replaces eligible direct references with
store-backed soft references, but does not sync the cursor or choose retained GC
roots; those remain application policy. `fork()` on a region, drive collection or
drive creates a temporary component which keeps the same store and only merges
logical changes when explicitly synced.

Lower-level embedders that deliberately open a drive from a cursor
instead of the component hierarchy must supply the host store explicitly:

```java
DLFSLocal drive = DLFS.connect(drivesCursor, driveName, store);
```

The store is not owned or closed by the filesystem. It should be the same store
used to publish the containing lattice root. The two-argument cursor overload has
no physical persistence policy and therefore keeps file data on heap.

`DLFSDriveManager` remains the service-facing local routing and mapping layer. It
can manage explicitly detached per-identity drives, or route identities to multiple
`DLFSDrives` components for WebDAV and MCP. Applications that span physical regions
or lattice roots choose and compose those mappings locally rather than encoding
them into a region.

Connect any WebDAV client, e.g.:

```bash
curl -X PROPFIND http://localhost:8080/dlfs/
```

## Design

DLFS supports NIO copy and move between drives, and ordinary `Files.copy` /
`Files.move` with other providers. Aliased views obtained from the same component
root recognise the same working drive; atomic moves are supported within that
drive. Cross-drive moves copy first and remove the source only if it still matches
the copied snapshot. A concurrent source edit leaves both versions and reports an
error. `ATOMIC_MOVE` across independent drives is rejected.

Nodes have an optional content modification time (`mtime`) independent of their
application-controlled merge clock. NIO `COPY_ATTRIBUTES` and moves preserve
`mtime`; ordinary copies use the destination operation time. Reads do not update
state. Access time is reported as `mtime`, creation time as the epoch, and setters
for these unsupported times are ignored. See the node format and compatibility
details in the design document.

See [docs/DLFS_DESIGN.md](docs/DLFS_DESIGN.md) for the architecture, and
[docs/DLFS_AUTH.md](docs/DLFS_AUTH.md) for the authentication model.

## License

Copyright 2017-2025 The Convex Foundation and Contributors

Code in convex-dlfs is provided under the [Convex Public License](../LICENSE.md).
