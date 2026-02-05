# Convex DB

SQL database layer for Convex lattice data.

## Overview

Convex DB provides SQL query capabilities over lattice data structures, built on Apache Calcite. It enables standard SQL access to KV databases, distributed filesystems, and other lattice-backed stores.

## Features

### Lattice Tables

A SQL-like table store with conflict-free merge replication:

```java
// Create a database
AKeyPair keyPair = AKeyPair.generate();
SQLDatabase db = SQLDatabase.create("mydb", keyPair);

// Create tables
db.tables().createTable("users", new String[]{"id", "name", "email"});

// Insert rows (primary key can be CVMLong, AString, or ABlob)
db.tables().insert("users", CVMLong.create(1),
    Vectors.of(CVMLong.create(1), Strings.create("Alice"), Strings.create("alice@example.com")));

// Query rows
AVector<ACell> row = db.tables().selectByKey("users", CVMLong.create(1));
Index<ABlob, AVector<ACell>> allRows = db.tables().selectAll("users");

// Delete rows
db.tables().deleteByKey("users", CVMLong.create(1));

// Drop tables
db.tables().dropTable("users");
```

### Replication

Tables support lattice-based replication between nodes:

```java
// Node 1
SQLDatabase db1 = SQLDatabase.create("shared", keyPair1);
db1.tables().createTable("data", new String[]{"id", "value"});
db1.tables().insert("data", key, values);

// Node 2
SQLDatabase db2 = SQLDatabase.create("shared", keyPair2);
db2.tables().createTable("data", new String[]{"id", "value"});

// Merge replicas
db1.mergeReplicas(db2.exportReplica());
```

### Merge Semantics

- **Tables**: LWW (last-write-wins) for schema changes
- **Rows**: LWW with row-level granularity
- **Conflicts**: Latest timestamp wins; equal timestamps favor deletions

## Lattice Structure

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

## Dependencies

- `convex-core` - Core data structures and lattice infrastructure
- `convex-peer` - Network replication and peer connectivity
- Apache Calcite - SQL parsing, planning, and execution

## Building

```bash
mvn clean install -pl convex-db
```

## Running Tests

```bash
mvn test -pl convex-db
```
