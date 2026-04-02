# Convex DB JDBC Driver Design

## Overview

The Convex DB JDBC driver provides standard SQL access to lattice-backed tables
within a single JVM. It is a **local, in-process driver** — there is no network
protocol between the JDBC client and the database. All data access goes through
the lattice cursor chain in shared memory.

For remote SQL access, use the PostgreSQL wire protocol server (`PgServer`).

## Architecture

```
Application Code
    │
    ├── Lattice API (direct)     ── ConvexDB → SQLDatabase → SQLSchema → SQLTable
    │                                   │
    ├── JDBC (local, in-process) ── ConvexDriver → Calcite → ConvexSchema ──┘
    │                                                                   (same cursor)
    └── PostgreSQL wire protocol ── PgServer → Calcite → ConvexSchema ──┘
                (remote)
```

All three access methods share the same underlying cursor chain. Writes via JDBC
are immediately visible to the lattice API and vice versa.

## URL Format

Follows standard JDBC conventions (H2, Derby, HSQLDB style):

```
jdbc:convex:mem:<dbName>                   — named in-memory database
jdbc:convex:file:<path>                    — persistent (Etch-backed)
jdbc:convex:<dbName>                       — shorthand for mem:<dbName>
```

Parameters are appended with semicolons:

```
jdbc:convex:file:/data/mydb.etch;caseSensitive=false
```

| Mode | URL | Description |
|------|-----|-------------|
| In-memory (named) | `jdbc:convex:mem:market` | Named in-memory database, shared across connections with the same name |
| In-memory (shorthand) | `jdbc:convex:market` | Same as `mem:market` |
| Persistent (file) | `jdbc:convex:file:/data/market.etch` | Etch-backed, survives JVM restart |
| Persistent (relative) | `jdbc:convex:file:data/market.etch` | Relative to working directory |

| Parameter | Default | Description |
|-----------|---------|-------------|
| `caseSensitive` | `false` | SQL identifier case sensitivity |
| `create` | `true` | Create database if it does not exist |

### Database Identification

The URL uniquely identifies the database — no separate registration step needed.

- **`mem:` databases** are identified by name. Two connections to `jdbc:convex:mem:market`
  share the same cursor chain and see each other's writes immediately.
- **`file:` databases** are identified by canonical file path. Two connections to the
  same Etch file share the same cursor chain.

This follows the same conventions as H2 (`jdbc:h2:mem:test`, `jdbc:h2:~/test`)
and HSQLDB (`jdbc:hsqldb:mem:test`, `jdbc:hsqldb:file:~/test`).

## Connection Model

### How It Works

The driver maintains an internal map of open database instances, keyed by
canonical identifier (name for `mem:`, absolute file path for `file:`). This
replaces the previous static registry that required manual `register()`/
`unregister()` calls.

```
DriverManager.getConnection("jdbc:convex:mem:market")
    │
    ├── Driver parses URL → mode=mem, name="market"
    ├── Looks up "mem:market" in open instances map
    │     ├── Found → return new Connection on existing cursor chain
    │     └── Not found → create ConvexDB + cursor, store in map, return Connection
    │
    └── Connection wraps Calcite + ConvexSchema backed by the cursor chain
```

For `file:` mode, the driver also manages the `EtchStore` and `NodeServer`
lifecycle:

```
DriverManager.getConnection("jdbc:convex:file:/data/market.etch")
    │
    ├── Driver parses URL → mode=file, path="/data/market.etch"
    ├── Canonicalises path → absolute path as key
    ├── Looks up in open instances map
    │     ├── Found → return new Connection on existing cursor/store
    │     └── Not found:
    │           ├── EtchStore.create(file)     — opens or creates Etch file
    │           ├── NodeServer(lattice, store)  — manages cursor + persistence
    │           ├── server.launch()             — restores from Etch if exists
    │           ├── ConvexDB.connect(cursor)    — wraps the cursor chain
    │           └── Store in map, return Connection
    │
    └── Connection wraps Calcite + ConvexSchema backed by the cursor chain
```

### One ConvexDB, Many Databases

A `ConvexDB` instance wraps a single lattice cursor at the database-map level.
It can host multiple named databases within that cursor:

```java
ConvexDB cdb = ConvexDB.create();
SQLDatabase db1 = cdb.database("market");    // cursor path: /market/:tables/...
SQLDatabase db2 = cdb.database("analytics"); // cursor path: /analytics/:tables/...
```

Each `database()` call navigates the cursor tree — it does not create a copy.

In the URL, a `ConvexDB` maps to the store/instance, and the database name
selects which path within the cursor tree:

```
jdbc:convex:file:/data/store.etch;database=market
```

If no `database` parameter is given, the driver uses a default database name
(the filename stem for `file:` mode, or the instance name for `mem:` mode).

### Direct API (No DriverManager)

Code that already holds a `ConvexDB` instance can get a Connection directly,
bypassing URL parsing and the instances map entirely:

```java
ConvexDB cdb = ConvexDB.connect(server.getCursor());
Connection conn = cdb.getConnection("market");
```

This is the preferred API when you manage the `NodeServer` lifecycle yourself.

## Usage Examples

### Quick Start (In-Memory)

```java
// Just connect — database is created automatically
Connection conn = DriverManager.getConnection("jdbc:convex:mydb");
Statement stmt = conn.createStatement();
stmt.executeUpdate("CREATE TABLE users (id INTEGER, name VARCHAR)");
stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice')");
ResultSet rs = stmt.executeQuery("SELECT * FROM users");
```

### Persistent Database

```java
// First run — creates Etch file and database
Connection conn = DriverManager.getConnection("jdbc:convex:file:/data/market.etch");
// ... create tables, insert data ...
conn.close();

// Later — reopens existing data
Connection conn2 = DriverManager.getConnection("jdbc:convex:file:/data/market.etch");
// All previous data is available
```

### Programmatic Setup (Full Control)

For advanced use cases (replication, custom NodeServer config):

```java
EtchStore store = EtchStore.create(new File("/data/market.etch"));
NodeServer<?> server = new NodeServer<>(lattice, store, config);
server.launch();

ConvexDB cdb = ConvexDB.connect(server.getCursor());
Connection conn = cdb.getConnection("market");
// ... use conn ...

server.close();  // persists on shutdown
store.close();
```

### Multiple Databases in One Store

```java
Connection conn = DriverManager.getConnection(
    "jdbc:convex:file:/data/store.etch;database=market");
Connection conn2 = DriverManager.getConnection(
    "jdbc:convex:file:/data/store.etch;database=analytics");
// Both share the same Etch file, different cursor paths
```

## Transaction Support

JDBC transactions use the lattice fork/sync model:

- `setAutoCommit(false)` → forks the cursor (snapshot isolation)
- `commit()` → syncs the fork back (lattice merge into parent)
- `rollback()` → discards the fork

Implementation: `ConvexMeta` (in `org.apache.calcite.jdbc` package).

## Query Pipeline

See [CALCITE.md](CALCITE.md) for the full Calcite adapter architecture.

```
SQL → Calcite Parser → Logical Plan → ConvexConvention operators → ConvexRelExecutor → ResultSet
```

Key optimisations:
- **PK filter pushdown**: `WHERE id = ?` → O(log n) Index lookup via `selectByKey()`
- **Plan caching**: PreparedStatements reuse compiled plans across executions
- **No materialisation**: full scans use `forEach` for single-pass O(n) traversal

## Persistence

For `file:` mode databases, the driver manages persistence automatically:

- **On connect**: opens (or creates) the Etch file, restores previous state
- **Periodic**: the underlying `NodeServer` persists at configurable intervals
- **On close**: final persist when the last connection to a store is closed

For `mem:` mode, data lives only in the JVM heap. It is lost when the last
connection is closed or the JVM exits.

## Relationship to PgServer

The PostgreSQL wire protocol server (`PgServer`) provides **remote** SQL access
over TCP using the PostgreSQL client protocol. It reuses the same `ConvexSchema`
and Calcite pipeline as the JDBC driver, but adds:

- Network transport (Netty)
- PG message framing (Parse/Bind/Execute/Describe)
- `pg_catalog` virtual schema for client tool compatibility
- Optional password authentication

Both JDBC and PgServer share the same cursor chain and see the same data.

## Migration from Registry API

The previous API required manual registration:

```java
// Old (deprecated)
cdb.register("mydb");
Connection conn = DriverManager.getConnection("jdbc:convex:database=mydb");
cdb.unregister("mydb");
```

The new API is self-contained:

```java
// New — URL-based (no registration needed)
Connection conn = DriverManager.getConnection("jdbc:convex:mydb");

// New — direct API (no registration needed)
Connection conn = cdb.getConnection("mydb");
```

The `register()`/`unregister()` methods remain for backward compatibility but
are no longer the primary API.
