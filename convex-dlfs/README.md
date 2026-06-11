# Convex DLFS

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-dlfs.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-dlfs/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-dlfs)

The **Data Lattice File System (DLFS)** — a decentralised, content-addressed,
CRDT-mergeable filesystem built on Convex immutable data structures, with Java NIO
compatibility and a WebDAV server for access from ordinary file clients.

## Overview

DLFS stores a whole filesystem as an immutable Convex lattice value. Because the
underlying data structures merge like CRDTs, independent replicas can be reconciled
without conflicts — making DLFS suitable for offline-first and decentralised storage.

It exposes the filesystem in three ways:

- **Java NIO `FileSystem` API** — use DLFS through standard `java.nio.file.Path` /
  `Files` operations.
- **WebDAV server** — mount or browse drives from curl, an OS file manager, or any
  WebDAV client. Drives appear as top-level directories under `/dlfs/{drive}/{path}`.
- **MCP tools** — drive operations are exposed as Model Context Protocol tools for use
  by AI agents.

## Features

- Content-addressed, immutable storage with CRDT-style `fork()` / `sync()` / `merge()`
- Java NIO `FileSystem` provider for drop-in file operations
- WebDAV (RFC 4918) server built on Javalin, with virtual-thread request handling
- Per-identity, multi-drive registry — each authenticated user gets their own drives
- Optional Ed25519 JWT bearer-token authentication
- Model Context Protocol (MCP) integration

## Installation

### Maven

```xml
<dependency>
    <groupId>world.convex</groupId>
    <artifactId>convex-dlfs</artifactId>
    <version>0.8.4</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-dlfs:0.8.4'
```

## Usage

### Run a WebDAV server

```java
import convex.dlfs.DLFSServer;
import convex.core.crypto.AKeyPair;

// Pass a key pair to require Ed25519 JWT auth, or null for no auth
DLFSServer server = DLFSServer.create(AKeyPair.generate());
server.start(8080); // use 0 for a random port

// Drives are now served under http://localhost:8080/dlfs/{drive}/{path}
```

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
