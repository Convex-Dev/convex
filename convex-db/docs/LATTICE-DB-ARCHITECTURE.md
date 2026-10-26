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
shared data such as reference tables. `ConvexDB.openHost(store, policy)` is a convenience that
assembles a host whose root is the database map, with a `ConvexDB` registered;
`ConvexDB.connect(host)` returns that instance.

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
`cursor.path(key)`, one level down, passing on the merge policy and clock. This is the same pattern as
`SocialUser.feed()` and `SocialUser.follows()` in convex-social.

Components hold no link to their parent. Constructing one reads and writes nothing:
connecting to a database that does not exist, or reading it, creates no state. The
first changed write creates the database, and the table store within it, through
the cursor defaults.

## Clock

Writes are stamped with a clock the application supplies, a `Supplier<CVMLong>`
given to `ConvexDB` (or to a standalone `SQLDatabase` or `SQLSchema`), defaulting to
the runtime clock. The factories pass it down to every database and table, and forks
and transactions keep it. Each write reads it once: creating or dropping a table
stamps the table entry, and inserting or deleting a row stamps the row. Merges never
read it, and incoming values keep their timestamps.

The clock is how a writer orders its writes, so it is the application's policy. A
single writer that needs every later write to win supplies strictly increasing
stamps; otherwise two writes in the same millisecond tie, and a tie keeps the value
already held.

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
  with the database's merge policy. This is atomic and in-memory — just a
  `compareAndSet` on the DB cursor's `AtomicReference`.
- **Rollback**: discard the transaction cursor. The DB cursor is untouched.
- **Conflict resolution**: the policy reconciles concurrent transactions, and is
  consulted only when both have changed the database. Under `RowLWW`, two
  transactions inserting different rows merge cleanly, and two updating the same
  row resolve by the later timestamp.

### Why this works

The transaction is a component fork: a `SQLDatabase` with its own working state,
snapshotting the database value at fork time, whose destination is the live cursor.
`sync()` merges the working value into the destination with the database's policy
and publishes the tables; the destination's other sections stay as they are.

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

## Merge Policy

How a database merges is not built into Convex DB: whatever constructs a database
supplies its merge policy, a pure function of the own and incoming database states
(`BinaryOperator<Index<Keyword, ACell>>`), and there is no default. An application
whose tables several writers update row by row can use the per-row last-write-wins
policy Convex DB provides, `RowLWW::merge`; one that serialises its updates, schema
changes included, supplies a policy that follows its own ordering.

The database is the unit of merging, as it is of transactions. `ConvexDB` passes its
policy to each database through `database(name)`, and merges the database map by name,
each database with that policy: nothing spans databases. The table store and tables
are live views for table operations, with no merge of their own, so they are not
forked separately: a transaction forks the database.

Every route by which state arrives reaches the policy:

- **Incoming values**: the host delivers them to its registered `ConvexDB`, which
  merges each database with its policy.
- **Path delivery**: a delivery below a database is lifted to a database state holding
  only what was delivered, then merged with the policy, so a delivery and a
  whole-value merge agree. The table store (`[database, :tables]`), a table entry
  (`[database, :tables, table]`), its rows (`[database, :tables, table, 1]`) or one row
  (`[database, :tables, table, 1, key]`) can be delivered; rows are lifted into a table
  entry without a `utime`. Deliveries to other sections are ignored, and other paths
  are refused.
- **Fork publication**: `sync()` reconciles a fork with its destination through the
  policy. `SQLDatabase` publishes only its tables: the destination's other sections
  stay as they are.
- **Replicas**: `SQLDatabase.mergeReplicas` verifies each signed replica with
  `acceptsReplica`, then merges its state with the policy.

### Per-row last-write-wins

`RowLWW` is a set of pure functions, one for each level:

| Level | Function | Rule |
|-------|----------|------|
| database | `merge` | `:tables` by `mergeTables`; other sections are ignored on receipt and kept where held |
| table store | `mergeTables` | union of table names; each entry by `mergeTable` |
| table entry | `mergeTable` | LWW schema on `utime`, own on a tie; a dropped table's tombstone wins in the same way; rows by `mergeRows`, whichever schema wins |
| rows | `mergeRows` | by key; a row held on one side only survives if written at or after the other side's `utime` |
| row entry | `mergeRow` | LWW by timestamp; equal timestamps favour deletion, otherwise own |

An entry's `utime` is the time the table was created or dropped: row writes leave it
unchanged. A row written before a table was created belongs to an earlier table of
that name, so when one side holds a row the other lacks, the row survives only if it
was written at or after the other side's `utime`. Rows written before a `DROP TABLE`
therefore never return, whether a copy missed the drop or the table was created again,
and every copy of a single writer's tables converges to the writer's own. `DROP TABLE`
writes only the tombstone: a dropped table holds just the rows written after it was
dropped, which no reader sees.

A table entry without a `utime` carries only rows, from a delivery below the entry: its
rows merge into a table that exists, live or dropped, as those of a whole entry would,
and an absent table takes nothing. Merges that accept nothing create nothing: a
database or table store that is absent stays absent. Equal timestamps keep the
established value, as described in the
[lattice application design](../../convex-core/docs/LATTICE_APPLICATIONS.md).

Who may change a table's schema, drop it or create it again, and how those changes
are ordered between writers, is application policy. Under `RowLWW`, two creations of a
table by different writers are different tables: rows written to the earlier one
before the later creation are dropped, while rows written after it still merge. A
table is therefore created once and replicated. An application with several writers
changing schemas sequences those changes itself, or supplies another policy.

### Composing a policy

The `RowLWW` functions are public, so an application can compose a variant from them.
For example, a policy that keeps the entry it holds for a reference table, so that no
merge changes it, and merges the other tables row by row:

```java
static Index<Keyword, ACell> keepReference(Index<Keyword, ACell> own, Index<Keyword, ACell> other) {
    Index<Keyword, ACell> merged = RowLWW.merge(own, other);
    AVector<ACell> held = reference(own);   // the "reference" entry of own's :tables, or null
    if (held == null || held.equals(reference(merged))) return merged;
    Index<AString, AVector<ACell>> tables = (Index<AString, AVector<ACell>>) merged.get(ConvexDB.KEY_TABLES);
    return merged.assoc(ConvexDB.KEY_TABLES, tables.assoc(REFERENCE, held));
}

ConvexDB cdb = ConvexDB.create(MyApp::keepReference);
```

`ConvexDBMergeTest` exercises such a policy across every route.

## Target API

```java
// Setup — the host's registered ConvexDB merges incoming values
var server = ConvexDB.createNodeServer(store, RowLWW::merge);
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

Each component owns its cursor, and each database merges with the policy its wrapper
supplies. The transaction fork gives atomic commit, free rollback and concurrent
merges by that policy without confusing persistence with cursor sync.
