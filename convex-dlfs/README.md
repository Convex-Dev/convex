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
directional, so applications must supply a sound mutation timestamp policy.

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
    <version>0.8.12</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-dlfs:0.8.12'
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

The NodeServer normally hosts `Lattice.ROOT`, with DLFS attached at `:fs`, so root
publication follows the real persistence and replication path. `dlfs.path` may place
the region under a custom, otherwise unused top-level region, for example
`["applications", "documents"]`. `dlfs.mounts` is deliberately local service policy:
it maps anonymous, the symbolic local `"node"` DID, or an explicit `did:key` identity
to the local owner (`owner: "node"`) or any replicated owner `AccountKey`. Replication
never makes another user's drives automatically visible over HTTP.

The equivalent programmatic setup is:

```java
EtchStore store = EtchStore.createTemp("convex-dlfs");
AKeyPair keyPair = AKeyPair.generate();
NodeServer node = new NodeServer(Lattice.ROOT, store, NodeConfig.port(19888));
node.setMergeContext(LatticeContext.create(null, keyPair));
node.launch();

DLFSApplication app = DLFSApplication.connect(node.getRootComponent(), Keywords.FS);
DLFSDriveManager drives = new DLFSDriveManager(
    app.drives(keyPair.getAccountKey()));
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
at any configured path and may be extended with other lattice facilities such
as P2P discovery. The stack accepts a generic `RootComponent` and has no
dependency on `NodeServer`.

```java
// Local: restore or create the standard :fs region in any AStore
DLFSApplication app = DLFSApplication.open(store, myKeyPair);
DLFSDriveManager myDrives = new DLFSDriveManager(
    app.drives(myKeyPair.getAccountKey()));
myDrives.createDrive(null, "home");
FileSystem home = myDrives.getDrive(null, "home");
Files.writeString(home.getPath("/hello.txt"), "Hello");
app.sync();  // select and persist the current lattice root
app.flush(); // separate physical durability barrier

DLFSServer server = DLFSServer.create(myDrives);
server.start(0);
```

Networked bootstrap supplies the host; the DLFS component API is unchanged:

```java
KeyedLattice lattice = KeyedLattice.create(
    Keyword.intern("documents"), DLFSRegion.LATTICE);
NodeServer<Index<Keyword, ACell>> node =
    new NodeServer<>(lattice, store, NodeConfig.port(0));
node.setMergeContext(LatticeContext.create(null, myKeyPair));
node.launch();

DLFSApplication networkedApp = DLFSApplication.connect(
    node.getRootComponent(), Keyword.intern("documents"));
DLFSDrives ownerDrives = networkedApp.drives(myKeyPair.getAccountKey());
DLFSDriveManager routes = DLFSDriveManager.createRouter()
    .mountAnonymous(ownerDrives)
    .mount(DID.forKey(myKeyPair.getAccountKey()).toString(), ownerDrives);
routes.createDrive(null, "home");
networkedApp.sync();

DLFSServer networkedServer = DLFSServer.createWithAudience(routes, myKeyPair);
networkedServer.start(0);
```

The component hierarchy is `RootComponent` → `DLFSApplication` → `DLFSRegion`
→ `DLFSDrives` → `DLFSDrive`. The application is not owner-scoped: it can obtain
components for multiple owners. Local identity-to-owner and cross-region mappings
remain `DLFSDriveManager` routing policy; the HTTP server owns no lattice or store
lifecycle.

The application does not own or close its root or store. Owner components are cheap
views and are not retained globally; long-lived services keep the `DLFSDrives`
components they route, which also retains their cached NIO views. Isolated work uses
`app.drives(owner).fork()` or `app.drives(owner).drive(name).fork()` and explicitly
syncs the temporary component when its changes should merge back.

Large channel writes checkpoint blob data through the component hierarchy every
16 MiB. This replaces eligible direct references with store-backed soft references,
but does not sync the cursor or choose retained GC roots; those remain application
policy. `fork()` on a region, drive collection or drive creates a temporary
component which keeps the same persistence policy and only merges logical changes
when explicitly synced.

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

See [docs/DLFS_DESIGN.md](docs/DLFS_DESIGN.md) for the architecture, and
[docs/DLFS_AUTH.md](docs/DLFS_AUTH.md) for the authentication model.

## License

Copyright 2017-2025 The Convex Foundation and Contributors

Code in convex-dlfs is provided under the [Convex Public License](../LICENSE.md).
