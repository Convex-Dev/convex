# Convex DB: Component and Cursor Architecture

## Cursor Chain

A SQL database is a tree of lattice cursors. Each component navigates to its
section of the tree via `cursor.path(key)`, which creates a descended cursor.

```
LatticeHost                     ← persistence and publication policy
  │
  └── ConvexDB                  ← registered application: database name → database
        │
        └── path("mydb")        ← SQLDatabase: :tables → table store
              │
              └── path(:tables) ← SQLSchema: table name → table entry
                    │
                    └── path("users") ← SQLTable: schema, rows, utime
```

Each `path()` call creates a lightweight descended cursor that reads/writes through
its parent. A `set()` or `updateAndGet()` at any level atomically propagates
up the chain to the root `AtomicReference`. No copies, no extra work — just
`assocIn` on the immutable tree and a `compareAndSet` at the root.

`ConvexDB` takes the cursor of a database map and assumes nothing about where that
lies. Where the databases live is the application's choice: it can root the map at
the host root, under a region of its own or inside another application, and it
registers the `ConvexDB` with its host to receive incoming values. By convention,
an application's own databases live under `:app`; `:sql` is intended for publicly
shared data such as reference tables. `ConvexDB.openHost(store)` is a convenience that assembles a host
whose root is the database map, with a `ConvexDB` registered; `ConvexDB.connect(host)`
returns that instance.

## Component ↔ Cursor Mapping

Each component extends `ALatticeComponent<V>`, wrapping one cursor in the chain:

```
LatticeHost   cursor: AHashMap<AString, Index>    hosted root
  └── ConvexDB  cursor: AHashMap<AString, Index>    database map (here the same cursor)
        └── SQLDatabase cursor: Index<Keyword, ACell>   path(database-name)
              └── SQLSchema cursor: Index<AString, AVector> path(:tables)
                    └── SQLTable cursor: AVector<ACell>      path(table-name)
```

`database()`, `tables()` and `table()` each construct the child component over
`cursor.path(key)`, one level down. This is the same pattern as
`SocialUser.feed()` and `SocialUser.follows()` in convex-social.

Components hold no link to their parent. Constructing one reads and writes nothing:
connecting to a database that does not exist, or reading it, creates no state. The
first changed write creates the database, and the table store within it, through
the cursor defaults.

## Cursor Descent

Each child component retains its descended cursor rather than repeatedly reading
and associating through raw parent values. Its `updateAndGet` operation delegates
the immutable update back through the cursor chain atomically. This encapsulates
navigation without adding another copy of the application state.

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
  with the database's merge rule. This is atomic and in-memory — just a
  `compareAndSet` on the DB cursor's `AtomicReference`.
- **Rollback**: discard the transaction cursor. The DB cursor is untouched.
- **Conflict resolution**: the table rules handle concurrent transactions. Two
  transactions inserting different rows merge cleanly. Two transactions updating
  the same row resolve via LWW (last timestamp wins).

### Why this works

The transaction is a component fork: a `SQLDatabase` with its own working state,
snapshotting the database value at fork time, whose destination is the live cursor.
`sync()` merges the working value into the destination with the database's rule,
which calls each table's rule. No locks, no conflict detection: the transaction's
changes merge with any made concurrently.

```java
ConvexDB cdb = ConvexDB.connect(server.getHost());
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
server.getHost().flush();
```

## Merge at Each Level

Merges are executed by the components. Each has a merge rule, `mergeValues(own, other)`:
a pure function of two values that reads and writes no state. A container's rule calls
the rule of the child component for each key, so no merge of Convex DB state bypasses
a child's rule:

| Level | Component | Merge rule |
|-------|-----------|------------|
| database map | `ConvexDB` | union of database names; each database by its `database(name)` component |
| database | `SQLDatabase` | `:tables` by its `tables()` component; other sections are ignored on receipt and kept where held |
| table store | `SQLSchema` | union of table names; each entry by its `table(name)` component |
| table entry | `SQLTable` | LWW schema on `utime`; while live, rows merge by key with `TableLattice` |
| row entry | (within `SQLTable`) | LWW by timestamp; equal timestamps favour deletion |

The same rules serve every route by which state arrives:

- **Incoming values**: the host delivers them to its registered `ConvexDB`, whose
  `merge` applies its rule.
- **Path delivery**: `mergeAt` resolves the path through `child(key)`, which returns
  the same components the rules use, and the deepest component merges the value.
- **Fork publication**: `sync()` reconciles a fork with its destination through
  `mergePublication`, which uses the rule. `SQLDatabase` publishes only its tables:
  the destination's other sections stay as they are.
- **Replicas**: `SQLDatabase.mergeReplicas` verifies each signed replica with
  `acceptsReplica`, then merges its state with the database's rule.

`SQLTable` is the merge boundary; there is no row component. A delivery to a row path
(`[database, :tables, table, 1, key]`, or `[database, :tables, table, 1]` with a row
index) is lifted into a rows-only partial entry and merged with the table's rule, so it
cannot change the schema. A table that is absent or dropped receives nothing, and other
paths into an entry are refused. Row writes leave the entry's `utime` unchanged: it is
the schema timestamp.

Merges that accept nothing create nothing: a database or table store that is absent
stays absent. Equal timestamps keep the established value, as described in the
[lattice application design](../../convex-core/docs/LATTICE_APPLICATIONS.md).

### Extending the rules

An application changes a rule by extending a component and supplying it from the
factory above it: `ConvexDB.database(name)`, `SQLDatabase.tables()` or
`SQLSchema.table(name)`. Each subclass also overrides `rebind`, so that forks and
transactions keep its type. The override then holds for every route above, for
example a reference table whose entry, once held, no merge changes:

```java
class ReferenceTable extends SQLTable {
    ReferenceTable(AForkableCursor<AVector<ACell>> cursor) { super(cursor); }
    @Override public AVector<ACell> mergeValues(AVector<ACell> own, AVector<ACell> other) {
        return (own != null) ? own : super.mergeValues(own, other);
    }
    @Override protected ReferenceTable rebind(AForkableCursor<AVector<ACell>> cursor) {
        return new ReferenceTable(cursor);
    }
}
```

The containing `SQLSchema` subclass returns it from `table(name)` for the names it
covers, and so on up to the `ConvexDB` the application registers with its host.
`ConvexDBMergeTest` exercises this across every route.

## Target API

```java
// Setup — the host's registered ConvexDB merges incoming values
var server = ConvexDB.createNodeServer(store);
server.launch();
ConvexDB cdb = ConvexDB.connect(server.getHost());
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
server.getHost().flush();
```

Each component owns its cursor and its merge rule. The transaction fork gives atomic
commit, free rollback and conflict-free concurrent merges without confusing
persistence with cursor sync.
