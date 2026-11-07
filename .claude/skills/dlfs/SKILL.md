---
name: dlfs
description: Work with DLFS, the Data Lattice File System — run a local DLFS server, manage drives, and read, write and list files through its MCP tools or WebDAV. Use when interacting with DLFS or working on the convex-dlfs module.
argument-hint: "[run|list|read|write|setup] [drive/path]"
---

# DLFS — Data Lattice File System

DLFS stores a whole filesystem as one immutable lattice value, so replicas merge
without coordination. It is exposed three ways: a Java NIO `FileSystem`, a WebDAV
server and MCP tools.

**Reference:** `convex-dlfs/README.md` for configuration, embedding and replication;
`convex-dlfs/docs/DLFS_DESIGN.md` for the node format and merge rules;
`convex-dlfs/docs/DLFS_AUTH.md` for authentication.

## Concepts

- **Drive** — an independent filesystem with its own root, such as `home`. Drives
  belong to an owner key; the server routes each HTTP identity to an owner's drives.
- **Merge** — newer timestamps win, directories merge recursively and deletes leave
  timestamped tombstones. On equal timestamps the local value wins. The application
  supplies the clock; DLFS never advances a timestamp itself.
- **Delegated drives** — another owner's drive is addressed by DID-URL
  (`did:key:zOwner.../drive`), with UCAN proofs passed in the tools' `ucans` argument.

## Run a local server

Build the fat jar, then start the standalone entry point:

```bash
./mvnw -B install -DskipTests
java -cp convex-integration/target/convex.jar convex.dlfs.Main
```

With no config this is a zero-setup local example: HTTP on `127.0.0.1:8080`, a
temporary Etch store, an ephemeral node key, a `home` drive and unauthenticated
writes. Nothing survives a restart. WebDAV is served at `/dlfs/` and MCP at `/mcp`.

Options: `--config <file.json5>` (or `CONVEX_DLFS_CONFIG`), `--http-port`,
`--node-port`, `--drive`, `--serve-inbound`. Start a config from the packaged
`convex-dlfs/src/main/resources/convex/dlfs/config-example.json5`. Set `node.store`
and `node.keypair` for anything that must persist; the file is then a secret.
`security.writeAuthentication` is `none` (default), `node-key` (mutations need a
bearer JWT) or `deny` (read-only). A non-loopback bind without write authentication
is rejected unless `allowUnauthenticatedHTTP: true`.

The CLI alternative, `convex dlfs start --etch <file>`, needs a stable keystore key
(`--key` or `--public-key`).

Check it is up:

```bash
curl -X PROPFIND http://localhost:8080/dlfs/
curl -X POST http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","params":{},"id":"1"}'
```

## MCP tools

Definitions live in `convex-dlfs/src/main/resources/convex/dlfs/mcp/tools/`. `ucans`
is optional on every per-file tool.

| Tool | Purpose | Arguments |
|------|---------|-----------|
| `dlfs_list_drives` | List the caller's drives | — |
| `dlfs_create_drive` | Create a drive; fails if it exists | `name` |
| `dlfs_delete_drive` | Delete a drive and everything in it (irreversible) | `name` |
| `dlfs_list` | List one directory; omit `path` for the root | `drive`, `path` |
| `dlfs_read` | Read a file; returns `{content, encoding, size}` | `drive`, `path` |
| `dlfs_write` | Write text; returns `{written, created}` | `drive`, `path`, `content` |
| `dlfs_mkdir` | Create one directory level | `drive`, `path` |
| `dlfs_delete` | Delete a file or empty directory | `drive`, `path` |

- `dlfs_read` returns text as `utf-8` and binary as `base64`. There is no `exists`
  tool: a missing file is an error from `dlfs_read`.
- Parent directories must exist. `dlfs_write` does not create them; create `src`
  before `src/main`.
- Listing is one level at a time. Each entry has `name`, `type` (`file` or
  `directory`) and `size` for files; walk subdirectories to explore a tree.
- Confirm with the user before `dlfs_delete_drive`.

Setting up a drive:

```
dlfs_create_drive  name=project
dlfs_mkdir  drive=project  path=src
dlfs_write  drive=project  path=README.md  content="# My Project"
```

## WebDAV

Drives appear as top-level directories under `/dlfs/{drive}/{path}`, and changes are
shared with the MCP tools. `MKCOL` on `/dlfs/{name}/` creates a drive.

```bash
curl http://localhost:8080/dlfs/home/readme.txt
curl -X PUT http://localhost:8080/dlfs/home/new.txt -d "Hello"
curl -X MKCOL http://localhost:8080/dlfs/home/newdir/
```

The server is DAV class 1: directory MOVE/COPY and locking are not implemented.

## Key files

| File | Purpose |
|------|---------|
| `convex-dlfs/src/main/java/convex/dlfs/Main.java` | Standalone entry point |
| `convex-dlfs/src/main/java/convex/dlfs/DLFSConfig.java` | JSON5 config: `node`, `dlfs`, `http`, `security` |
| `convex-dlfs/src/main/java/convex/dlfs/DLFSApplication.java` | Lattice application hosting the DLFS region |
| `convex-dlfs/src/main/java/convex/dlfs/DLFSServer.java` | HTTP server (WebDAV and MCP) |
| `convex-dlfs/src/main/java/convex/dlfs/DLFSWebDAV.java` | WebDAV handlers |
| `convex-dlfs/src/main/java/convex/dlfs/DlfsMcpTools.java` | MCP tool implementations |
| `convex-dlfs/src/main/java/convex/dlfs/DLFSDriveManager.java` | Identity-to-drive routing |
| `convex-core/src/main/java/convex/lattice/fs/DLFSNode.java` | Pure merge and navigation functions |
| `convex-core/src/main/java/convex/lattice/fs/DLFS.java` | Factory methods |
