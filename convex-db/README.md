# Convex DB

SQL database layer for Convex lattice data, with JDBC and PostgreSQL wire protocol support.

## Overview

Convex DB provides SQL query capabilities over lattice data structures, built on Apache Calcite. It enables standard SQL access to Convex databases through:

- **JDBC Driver** - Standard Java database connectivity
- **PostgreSQL Protocol** - Connect with psql, DBeaver, DataGrip, Metabase, and other PostgreSQL clients
- **Lattice Tables API** - Direct programmatic access with conflict-free replication

## Quick Start

```java
// 1. Create a ConvexDB, choosing how its databases merge
ConvexDB cdb = ConvexDB.create(RowLWW::merge);

// 2. Connect via JDBC to a named database within it
Connection conn = cdb.getConnection("mydb");

// 3. Use standard SQL
Statement stmt = conn.createStatement();
stmt.execute("CREATE TABLE users (id, name, email)");
stmt.execute("INSERT INTO users VALUES (1, 'Alice', 'alice@example.com')");
ResultSet rs = stmt.executeQuery("SELECT * FROM users WHERE id = 1");
```

## JDBC Usage

For a durable local installation, open a host over an Etch store. Networking is
optional and does not control saving:

```java
try (var store = EtchStore.create(file); var host = ConvexDB.openHost(store, RowLWW::merge)) {
    SQLDatabase db = ConvexDB.connect(host).database("mydb");
    // Perform database operations through this live view.
    host.checkpoint();
}
```

`checkpoint()` retains the current host snapshot and completes the store's
durability barrier. Closing the host does so once more, then the store closes.
For batched saving, use `host.sync()` after accepted edits and scope
`host.autoFlush(interval)` around the service lifetime; closing the host stops
it. With a `NodeServer`, stop request handling and call `server.stopIngress()`
before closing the host, then close the server, which sends the host's final
checkpoint to peers. Closing a `NodeServer` saves nothing itself. See the
executable local and JDBC reopen scenarios in
[`ConvexDBPersistenceTest`](src/test/java/convex/db/ConvexDBPersistenceTest.java).

Convex DB provides a standard JDBC driver for SQL access.

### Connection URL

```
jdbc:convex:<name>              — named in-memory database (also jdbc:convex:mem:<name>)
jdbc:convex:file:<path>         — persistent, Etch-backed database
jdbc:convex:database=<name>     — a ConvexDB registered with register(<name>)
```

[docs/JDBC.md](docs/JDBC.md) describes the URL parameters and when file-backed
databases are saved.

The `jdbc:convex:` driver is local and in-process: there is no network protocol
between the JDBC client and the database, so the URL takes no host or port. To
reach a database in another process, run the
[PostgreSQL Protocol Server](#postgresql-protocol-server) there and connect with
a PostgreSQL client or driver.

### Basic Usage

```java
import java.sql.*;

// Driver auto-registers via ServiceLoader
Connection conn = DriverManager.getConnection("jdbc:convex:mydb");

// DDL
Statement stmt = conn.createStatement();
stmt.execute("CREATE TABLE products (id, name, price)");
stmt.execute("DROP TABLE old_table");

// DML
stmt.execute("INSERT INTO products VALUES (1, 'Widget', 9.99)");
stmt.execute("UPDATE products SET price = 12.99 WHERE id = 1");
stmt.execute("DELETE FROM products WHERE id = 1");

// Queries
ResultSet rs = stmt.executeQuery("SELECT * FROM products WHERE price > 10");
while (rs.next()) {
    System.out.println(rs.getLong("id") + ": " + rs.getString("name"));
}

// Prepared statements
PreparedStatement pstmt = conn.prepareStatement(
    "INSERT INTO products VALUES (?, ?, ?)");
pstmt.setLong(1, 2);
pstmt.setString(2, "Gadget");
pstmt.setDouble(3, 19.99);
pstmt.executeUpdate();
```

### Supported SQL

Convex DB supports standard SQL via Apache Calcite:

- **DDL**: `CREATE TABLE`, `DROP TABLE`
- **DML**: `INSERT`, `UPDATE`, `DELETE`
- **Queries**: `SELECT`, `WHERE`, `ORDER BY`, `LIMIT`, `OFFSET`
- **Joins**: `INNER JOIN`, `LEFT JOIN`, `RIGHT JOIN`, `FULL OUTER JOIN`, `CROSS JOIN`
- **Set operations and subqueries**: `UNION [ALL]`, `INTERSECT`, `EXCEPT`, `IN` and `EXISTS` subqueries
- **Aggregations**: `GROUP BY`, `HAVING`, `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`
- **Expressions**: Arithmetic, string functions, `CASE`, `COALESCE`, `CAST`

### Transactions

JDBC transaction support uses Convex's lattice component fork/sync model:

```java
conn.setAutoCommit(false);

stmt.execute("INSERT INTO users VALUES (2, 'Bob', 'bob@example.com')");
// Changes isolated to this connection until commit

conn.commit();   // Syncs fork back to parent — now visible to other connections
// conn.rollback() would discard the fork instead
```

At the lattice level, `SQLDatabase.fork()` creates an isolated copy. `sync()` merges changes back; discarding the fork is a rollback.

## PostgreSQL Protocol Server

Connect to Convex DB using any PostgreSQL client.

### Starting the Server

```java
// Create and register database
ConvexDB cdb = ConvexDB.create(RowLWW::merge);
cdb.database("mydb");
cdb.register("mydb");

// Start PostgreSQL server
PgServer server = PgServer.builder()
    .port(5432)
    .database("mydb")
    .password("secret")  // Optional; omit for trust auth
    .build();
server.start();
```

### Command Line

```bash
java -cp convex-db.jar convex.db.psql.PgServer -p 5432 -d mydb
```

### Connecting with Clients

**psql:**
```bash
psql -h localhost -p 5432 -d mydb
```

**JDBC (PostgreSQL driver):**
```java
Connection conn = DriverManager.getConnection(
    "jdbc:postgresql://localhost:5432/mydb", "user", "password");
```

**Python (psycopg2):**
```python
import psycopg2
conn = psycopg2.connect(host="localhost", port=5432, dbname="mydb")
```

### Compatible Tools

The PostgreSQL server works with:

- **CLI**: psql
- **GUI**: DBeaver, DataGrip, pgAdmin, Beekeeper Studio
- **BI Tools**: Metabase, Superset, Grafana
- **Drivers**: JDBC, psycopg2, node-postgres, Go pq

### Limitations

Some PostgreSQL-specific features are not yet supported:

- Regex operators (`~`, `!~`, `~*`)
- Some system catalog tables (pg_proc, pg_index, pg_constraint)
- PostgreSQL-specific functions
- Serialisable transactions: a transaction reads a snapshot taken when it begins, and
  merges with concurrent changes at commit by the database's policy

## Lattice Tables API

For direct programmatic access without SQL overhead.

### Creating Tables

```java
SQLDatabase db = SQLDatabase.create("mydb", keyPair, RowLWW::merge);
SQLSchema schema = db.tables();

// Create table with column names
schema.createTable("users", new String[]{"id", "name", "email"});

// Check existence
boolean exists = schema.tableExists("users");

// List tables
String[] names = schema.getTableNames();

// Drop table
schema.dropTable("users");
```

### Row Operations

```java
// Insert - the first column is the primary key (CVMLong, AString, or ABlob)
AVector<ACell> values = Vectors.of(
    CVMLong.create(1),
    Strings.create("Alice"),
    Strings.create("alice@example.com")
);
schema.insert("users", values);

// Insert with auto-conversion from Java values (first value is the primary key)
schema.insert("users", 2, "Bob", "bob@example.com");

// Select by key
AVector<ACell> row = schema.selectByKey("users", CVMLong.create(1));

// Select all rows
Index<ABlob, AVector<ACell>> allRows = schema.selectAll("users");

// Delete by key
schema.deleteByKey("users", CVMLong.create(1));
```

### Schema Information

```java
// Get column names
String[] columns = schema.getColumnNames("users");

// Get column count
int count = schema.getColumnCount("users");
```

## Replication

Databases replicate by lattice merge. How two copies of a database merge is a policy
the application supplies when it creates a `ConvexDB` or `SQLDatabase`; there is no
default. The policy merges incoming values, deliveries, transactions and replicas
alike; see [Merge Policy](docs/LATTICE-DB-ARCHITECTURE.md#merge-policy).

### Per-row last-write-wins

`RowLWW::merge` is the policy Convex DB provides for tables that several writers
update row by row:

- **Tables**: Last-write-wins (LWW) for schema changes
- **Rows**: LWW with row-level granularity
- **Conflicts**: Latest timestamp wins; equal timestamps favour deletions
- **Re-creation**: Rows written before a table was created belong to an earlier table
  of that name, so rows from before a `DROP TABLE` never return

An application that serialises its updates, schema changes included, supplies its
own policy, or composes one from the `RowLWW` functions.

### Replicating Between Nodes

A table is created once and replicated; if each node created it, the later creation
would be a different table, without the rows written to the earlier one before it.

```java
// Node 1: Create and populate
SQLDatabase db1 = SQLDatabase.create("shared", keyPair1, RowLWW::merge);
db1.tables().createTable("data", new String[]{"id", "value"});
db1.tables().insert("data", 1, "hello");  // first column is the primary key

// Node 2: Receive the table from Node 1, then write
SQLDatabase db2 = SQLDatabase.create("shared", keyPair2, RowLWW::merge);
db2.mergeReplicas(db1.exportReplica());
db2.tables().insert("data", 2, "world");

// Merge Node 2's changes into Node 1
db1.mergeReplicas(db2.exportReplica());
```

### Replica Ownership

`mergeReplicas` accepts a replica when its signer is authorised for the owner it is
published under and its signature verifies ([CAD038](https://docs.convex.world/docs/cad/lattice_auth)).
By default, an owner that is an account key, or a self-certifying `did:key`, must be the
signer, and any other owner, such as an account address or a `did:web`, is refused. To
support other owners, supply an owner verifier. It replaces the default and decides for
every owner, account keys included:

```java
// (owner, signer key) -> whether that key may sign for the owner
BiPredicate<ACell, AccountKey> verifier = (owner, key) -> authority.isAuthorised(owner, key);
SQLDatabase db = SQLDatabase.create("shared", keyPair, Address.create(1337), verifier, RowLWW::merge);
```

The verifier must be thread-safe and use authority data already held locally. The same
rule applies to `exportReplica`, which throws if this node's key is not authorised for
its owner, so a node never publishes a replica that its peers would refuse.

### Selective Replication

To apply another rule, such as trusting only known keys, override `acceptsReplica` in a
subclass of `SQLDatabase`. The override also governs this node's own export, so a set of
trusted keys includes the node's own:

```java
@Override
protected boolean acceptsReplica(ACell owner, SignedData<?> replica) {
    return trustedKeys.contains(replica.getAccountKey()) && super.acceptsReplica(owner, replica);
}
```

## Data Types

Convex DB maps between SQL types and Convex lattice types (see [SQL-TYPES.md](docs/SQL-TYPES.md)):

| SQL Type | Convex Type | Notes |
|----------|-------------|-------|
| BIGINT | AInteger | Any CVM integer; read as `NUMERIC` with scale 0 |
| INTEGER | CVMLong | 64-bit signed integer; read as `BIGINT` |
| DOUBLE | CVMDouble | 64-bit floating point |
| VARCHAR | AString | Unicode string |
| BOOLEAN | CVMBool | true/false |
| VARBINARY | ABlob | Binary data |
| ANY | ACell | Any Convex value |

## Architecture

### Lattice Structure

A database map, held wherever the application roots it:
```
AHashMap<AString, Index>
  db-name → {:tables table-store}
```

Where table-store is:
```
Index<AString, AVector>
  table-name → [schema, rows, utime, live-count, version]
```

A version 0 entry has no `version` field
([CAD039](https://docs.convex.world/docs/cad/convex_sql)).

A replica exported by `SQLDatabase.exportReplica` is
`owner-key → signed(db-name → database)`.

And rows is:
```
Index<ABlob, AVector>
  primary-key → [values, utime, deleted]
```

### Module Structure

```
convex.db             - ConvexDB root lattice component
convex.db.lattice     - SQLDatabase, SQLSchema, SQLTable, SQLRow, RowLWW
convex.db.calcite     - Apache Calcite integration
convex.db.jdbc        - JDBC driver
convex.db.psql        - PostgreSQL wire protocol server
```

## Dependencies

- `convex-core` - Core data structures and lattice infrastructure
- `convex-peer` - Network replication (optional)
- Apache Calcite - SQL parsing, planning, and execution
- Netty - PostgreSQL server networking

## Building

```bash
./mvnw -B -T1C install -pl convex-db -am
```

## Running Tests

```bash
./mvnw -B -T1C test -pl convex-db -am
```

## Examples

See the test classes for more examples:

- `SQLDatabaseTest` - Database and replication
- `ConvexDriverTest` - JDBC usage
- `PgServerTest` - PostgreSQL protocol
