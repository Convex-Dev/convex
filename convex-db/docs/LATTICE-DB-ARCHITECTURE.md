# Convex DB: Component and Cursor Architecture

## Cursor Chain

A SQL database is a tree of lattice cursors. Each component navigates to its
section of the tree via `cursor.path(key)`, which creates a descended cursor.

```
NodeServer RootComponent       ← persistence and publication policy
  │
  └── ConvexDB / RootCursor    ← database-map application component
        │
        └── path("mydb")       ← MapLattice: db name → database
              │
              └── path(:tables) ← KeyedLattice: keyword → section
                    │
                    └── path("users") ← TableStoreLattice: table name → table state
```

Each `path()` call creates a lightweight descended cursor that reads/writes through
its parent. A `set()` or `updateAndGet()` at any level atomically propagates
up the chain to the root `AtomicReference`. No copies, no extra work — just
`assocIn` on the immutable tree and a `compareAndSet` at the root.

## Component ↔ Cursor Mapping

Each component extends `ALatticeComponent<V>`, wrapping one cursor in the chain:

```
RootComponent   cursor: AHashMap<AString, Index>    hosted root
  └── ConvexDB  cursor: AHashMap<AString, Index>    same cursor, application policy child
        └── SQLDatabase cursor: Index<Keyword, ACell>   path(database-name)
              └── SQLSchema cursor: Index<AString, AVector> path(:tables)
                    └── SQLTable cursor: AVector<ACell>      path(table-name)
```

`database()`, `tables()` and `getTable()` each call `cursor.path(key)` to descend one level.
The returned component wraps the descended cursor. This is the same pattern as
`SocialUser.feed()` and `SocialUser.follows()` in convex-social.

The component-parent chain is deliberately separate from the cursor-parent chain.
Calling `persist()` delegates from `SQLTable` through `SQLSchema`, `SQLDatabase`
and `ConvexDB` to `RootComponent` without changing or synchronising any cursor. A fork keeps the
same containing component parent while its forked cursor synchronises to the live
cursor it came from.

## Cursor Descent

Each child component retains its descended cursor rather than repeatedly reading
and associating through raw parent values. Its `updateAndGet` operation delegates
the immutable update back through the cursor chain atomically. This encapsulates
navigation and preserves the lattice type at every path without adding another
copy of the application state.

## Transaction Forks

`ConvexDB.database()` returns a live path beneath the hosted database map. A
transaction forks that `SQLDatabase`; this gives atomic commit and cheap rollback:

```
RootCursor / ConvexDB              ← hosted database map
  │
  └── SQLDatabase cursor           ← live database path
        │
        └── Transaction cursor     ← forked from SQLDatabase
        sync() = atomic merge into DB cursor
        (discard = rollback, DB unchanged)
```

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

The transaction uses a `ForkedLatticeCursor` — a separate `AtomicReference` that
snapshots the database value at fork time. `sync()` calls
`parent.merge(localValue)` which uses the lattice merge at each level of the
tree. No locks, no conflict detection — the lattice algebra guarantees
convergence.

```java
ConvexDB cdb = ConvexDB.connect(server.getRootComponent());
SQLDatabase db = cdb.database("mydb");

// Transaction 1
SQLDatabase tx1 = db.fork();
tx1.tables().insert("users", row1);
tx1.sync();  // merge into db cursor

// Transaction 2
SQLDatabase tx2 = db.fork();
tx2.tables().insert("users", row2);
tx2.sync();  // merge into db cursor (both rows now visible)

// Publish the hosted application root, then request physical durability
cdb.sync();
server.getRootComponent().flush();
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
// Setup — ConvexDB is attached to the NodeServer policy root
var server = ConvexDB.createNodeServer(store);
server.launch();
ConvexDB cdb = ConvexDB.connect(server.getRootComponent());
SQLDatabase db = cdb.database("mydb");

// DDL (directly on db, outside transaction)
db.tables().createTable("users", columns, types);

// Transaction — fork from db
SQLDatabase tx = db.fork();
tx.tables().insert("users", row1);
tx.tables().insert("users", row2);
tx.tables().deleteByKey("users", oldPk);
tx.sync();    // commit: atomic merge into db cursor

// Publish and flush through host policy
cdb.sync();
server.getRootComponent().flush();
```

Each component owns its cursor and delegates application policy through its
component parent. The transaction fork gives atomic commit, free rollback and
conflict-free concurrent merges without confusing persistence with cursor sync.
