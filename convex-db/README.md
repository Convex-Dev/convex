# Convex DB

SQL database layer for Convex lattice data, with JDBC and PostgreSQL wire protocol support.

## Overview

Convex DB provides SQL query capabilities over lattice data structures, built on Apache Calcite. It enables standard SQL access to Convex databases through:

- **JDBC Driver** - Standard Java database connectivity
- **PostgreSQL Protocol** - Connect with psql, DBeaver, DataGrip, Metabase, and other PostgreSQL clients
- **Lattice Tables API** - Direct programmatic access with conflict-free replication

## Quick Start

```java
// 1. Create a database
AKeyPair keyPair = AKeyPair.generate();
SQLDatabase db = SQLDatabase.create("mydb", keyPair);

// 2. Register it for JDBC/PostgreSQL access
ConvexSchemaFactory.register("mydb", db);

// 3. Connect via JDBC
Connection conn = DriverManager.getConnection("jdbc:convex:database=mydb");

// 4. Use standard SQL
Statement stmt = conn.createStatement();
stmt.execute("CREATE TABLE users (id, name, email)");
stmt.execute("INSERT INTO users VALUES (1, 'Alice', 'alice@example.com')");
ResultSet rs = stmt.executeQuery("SELECT * FROM users WHERE id = 1");
```

## JDBC Usage

Convex DB provides a standard JDBC driver for SQL access.

### Connection URL

```
jdbc:convex:database=<database-name>
```

### Basic Usage

```java
import java.sql.*;

// Driver auto-registers via ServiceLoader
Connection conn = DriverManager.getConnection("jdbc:convex:database=mydb");

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
- **Joins**: `INNER JOIN`, `LEFT JOIN`, `RIGHT JOIN`, `CROSS JOIN`
- **Aggregations**: `GROUP BY`, `HAVING`, `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`
- **Expressions**: Arithmetic, string functions, `CASE`, `COALESCE`, `CAST`

### Transactions

JDBC transaction support uses Convex's lattice cursor fork/sync model:

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
SQLDatabase db = SQLDatabase.create("mydb", keyPair);
ConvexSchemaFactory.register("mydb", db);

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
- Transaction isolation across connections (read-committed level via lattice fork/sync)

## Lattice Tables API

For direct programmatic access without SQL overhead.

### Creating Tables

```java
SQLDatabase db = SQLDatabase.create("mydb", keyPair);
LatticeTables tables = db.tables();

// Create table with column names
tables.createTable("users", new String[]{"id", "name", "email"});

// Check existence
boolean exists = tables.tableExists("users");

// List tables
String[] names = tables.getTableNames();

// Drop table
tables.dropTable("users");
```

### Row Operations

```java
// Insert - primary key can be CVMLong, AString, or ABlob
ACell key = CVMLong.create(1);
AVector<ACell> values = Vectors.of(
    CVMLong.create(1),
    Strings.create("Alice"),
    Strings.create("alice@example.com")
);
tables.insert("users", key, values);

// Select by key
AVector<ACell> row = tables.selectByKey("users", key);

// Select all rows
Index<ABlob, AVector<ACell>> allRows = tables.selectAll("users");

// Delete by key
tables.deleteByKey("users", key);
```

### Schema Information

```java
// Get column names
String[] columns = tables.getColumnNames("users");

// Get column count
int count = tables.getColumnCount("users");
```

## Replication

Tables support lattice-based replication with conflict-free merging.

### Merge Semantics

- **Tables**: Last-write-wins (LWW) for schema changes
- **Rows**: LWW with row-level granularity
- **Conflicts**: Latest timestamp wins; equal timestamps favour deletions

### Replicating Between Nodes

```java
// Node 1: Create and populate
SQLDatabase db1 = SQLDatabase.create("shared", keyPair1);
db1.tables().createTable("data", new String[]{"id", "value"});
db1.tables().insert("data", key, values);

// Node 2: Create same structure
SQLDatabase db2 = SQLDatabase.create("shared", keyPair2);
db2.tables().createTable("data", new String[]{"id", "value"});

// Merge Node 2's changes into Node 1
db1.mergeReplicas(db2.exportReplica());

// Merge Node 1's changes into Node 2
db2.mergeReplicas(db1.exportReplica());
```

### Selective Replication

```java
// Only merge replicas from trusted keys
Predicate<AccountKey> trustFilter = key -> trustedKeys.contains(key);
db1.mergeReplicas(remoteReplicas, trustFilter);
```

## Data Types

Convex DB maps between SQL types and Convex lattice types:

| SQL Type | Convex Type | Notes |
|----------|-------------|-------|
| BIGINT | CVMLong | 64-bit signed integer |
| DOUBLE | CVMDouble | 64-bit floating point |
| VARCHAR | AString | Unicode string |
| BOOLEAN | CVMBool | true/false |
| VARBINARY | ABlob | Binary data |
| ANY | ACell | Any Convex value |

## Architecture

### Lattice Structure

```
:sql → OwnerLattice → SignedLattice → MapLattice → TableStoreLattice
        owner-key → signed(db-name → table-store)
```

Where table-store is:
```
Index<AString, AVector>
  table-name → [schema, rows, utime]
```

And rows is:
```
Index<ABlob, AVector>
  primary-key → [values, utime, deleted]
```

### Module Structure

```
convex.db.lattice    - Lattice-backed table storage
convex.db.calcite    - Apache Calcite integration
convex.db.jdbc       - JDBC driver
convex.db.psql       - PostgreSQL wire protocol server
```

## Dependencies

- `convex-core` - Core data structures and lattice infrastructure
- `convex-peer` - Network replication (optional)
- Apache Calcite - SQL parsing, planning, and execution
- Netty - PostgreSQL server networking

## Building

```bash
mvn clean install -pl convex-db
```

## Running Tests

```bash
mvn test -pl convex-db
```

## Examples

See the test classes for more examples:

- `SQLDatabaseTest` - Database and replication
- `ConvexDriverTest` - JDBC usage
- `PgServerTest` - PostgreSQL protocol
