# Convex DB: Cursor Architecture

## Cursor Chain

A SQL database is a tree of lattice cursors. Each component navigates to its
section of the tree via `cursor.path(key)`, which creates a `PathCursor`.

```
NodeServer RootCursor          ← AtomicReference, persistence + network
  │
  └── path("mydb")             ← MapLattice: db name → database
        │
        └── path(:tables)      ← KeyedLattice: keyword → section
              │
              └── path("users") ← TableStoreLattice: table name → table state
```

Each `path()` call creates a lightweight `PathCursor` that reads/writes through
its parent. A `set()` or `updateAndGet()` at any level atomically propagates
up the chain to the root `AtomicReference`. No copies, no extra work — just
`assocIn` on the immutable tree and a `compareAndSet` at the root.

## Component ↔ Cursor Mapping

Each component extends `ALatticeComponent<V>`, wrapping one cursor in the chain:

```
SQLDatabase     cursor: Index<Keyword, ACell>       path from db-map level
  │
  ├── schema()  cursor: Index<AString, AVector>     path(:tables)
  │     │
  │     └── getTable("users")
  │           cursor: AVector<ACell>                path("users")
  │
  └── (future sections at same level as :tables)
```

`schema()` and `getTable()` each call `cursor.path(key)` to descend one level.
The returned component wraps the descended cursor. This is the same pattern as
`SocialUser.feed()` and `SocialUser.follows()` in convex-social.

## Why PathCursor Is Free

Currently `SQLSchema.insert()` does this manually:

```java
cursor.updateAndGet(store -> {
    SQLTable table = SQLTable.wrap(store.get(tableName));
    // ... modify rows ...
    return store.assoc(tableName, table.withRows(rows, ts).getState());
});
```

With `SQLTable` owning a `PathCursor`, the same insert becomes:

```java
cursor.updateAndGet(tableState -> {
    // ... modify rows ...
    return withRows(rows, ts).getState();
});
```

These do **identical work**. The PathCursor's `updateAndGet` internally does
`parent.updateAndGet(root -> root.assoc(key, fn(root.get(key))))` — exactly
the manual `store.get(tableName)` + `store.assoc(tableName, ...)` that
`SQLSchema` currently does by hand. Moving the cursor down one level just
encapsulates the navigation; it doesn't add overhead.

## Two-Level Fork: Database + Transaction

The cursor chain has two fork boundaries, giving atomic transactions with
cheap rollback:

```
NodeServer RootCursor              ← persistence + network
  │
  └── SQLDatabase cursor           ← forked from root
  │     sync() = persist to Etch + broadcast
  │
  └── Transaction cursor           ← forked from DB
        sync() = atomic merge into DB cursor
        (discard = rollback, DB unchanged)
```

### Database level (`SQLDatabase`)

The database wraps a **forked cursor** off the NodeServer root. All operations
accumulate in this fork. `db.sync()` merges into the NodeServer cursor,
triggering persistence (Etch write) and network broadcast.

This means the database is always one step removed from persistence. You can
do significant work — multiple transactions — before persisting.

### Transaction level

Each transaction forks from the database cursor. Within a transaction, all
reads see the snapshot at fork time, and all writes accumulate locally.

- **Commit**: `tx.sync()` merges the transaction fork back into the DB cursor
  via lattice merge. This is atomic and in-memory — just a `compareAndSet` on
  the DB cursor's `AtomicReference`.
- **Rollback**: discard the transaction cursor. The DB cursor is untouched.
- **Conflict resolution**: lattice merge semantics handle concurrent
  transactions. Two transactions inserting different rows merge cleanly.
  Two transactions updating the same row resolve via LWW (last timestamp wins).

### Why this works

Both levels are just `ForkedLatticeCursor` — a separate `AtomicReference` that
snapshots the parent value at fork time. `sync()` calls
`parent.merge(localValue)` which uses the lattice merge at each level of the
tree. No locks, no conflict detection — the lattice algebra guarantees
convergence.

```java
// Open database (forks from NodeServer)
SQLDatabase db = SQLDatabase.connect(server.getCursor(), "mydb");

// Transaction 1
SQLDatabase tx1 = db.fork();
tx1.schema().getTable("users").insert(row1);
tx1.sync();  // merge into db cursor

// Transaction 2
SQLDatabase tx2 = db.fork();
tx2.schema().getTable("users").insert(row2);
tx2.sync();  // merge into db cursor (both rows now visible)

// Persist
db.sync();   // push to NodeServer → Etch + network
```

## Lattice Merge at Each Level

Each cursor level has a lattice type that defines merge semantics for replication:

| Level | Lattice | Merge Strategy |
|-------|---------|---------------|
| db map | `MapLattice` | union of db names, per-db merge |
| database | `KeyedLattice` | per-keyword section merge |
| table store | `TableStoreLattice` (`IndexLattice`) | union of table names, per-table merge |
| table entry | `SQLTableLattice` | LWW schema, row-level merge |
| row index | `TableLattice` (`IndexLattice`) | union of PKs, per-row merge |
| row entry | `RowLattice` | LWW by timestamp |

Merge happens when `sync()` reaches a `ForkedLatticeCursor` (local fork) or
when the NodeServer receives remote state (replication). The lattice guarantees
convergence regardless of merge order.

## Target API

```java
// Setup — db cursor is forked from NodeServer
NodeServer<?> server = SQLDatabase.createNodeServer(store);
server.launch();
SQLDatabase db = SQLDatabase.connect(server.getCursor(), "mydb");

// DDL (directly on db, outside transaction)
db.schema().createTable("users", columns, types);

// Transaction — fork from db
SQLDatabase tx = db.fork();
SQLTable users = tx.schema().getTable("users");
users.insert(row1);
users.insert(row2);
users.deleteByKey(oldPk);
tx.sync();    // commit: atomic merge into db cursor

// Persist to storage
db.sync();    // push to NodeServer → Etch + broadcast
```

Each component owns its cursor. The two fork boundaries (NodeServer → DB,
DB → transaction) give atomic transactions with free rollback and
conflict-free concurrent merges.
