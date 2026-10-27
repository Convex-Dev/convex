# ConvexConvention — Calcite Adapter Architecture

## Overview

Convex-DB uses a custom Calcite convention (`CONVEX`) to keep CVM types (`ACell[]`) throughout the query execution pipeline, converting to Java types only at the JDBC boundary. This follows Calcite's standard adapter pattern used by the JDBC, MongoDB, and Cassandra adapters.

## Architecture

```
SQL "SELECT name FROM t WHERE id = 1"
         ↓
    Calcite Parser / Validator
         ↓
    Logical Plan (LogicalFilter, LogicalProject, etc.)
         ↓
    ConvexTable.toRel() → ConvexTableScan (CONVEX convention)
         ↓
    ConvexRules convert logical operators → ConvexFilter, ConvexSort, etc.
         ↓
    ConvexToEnumerableConverter (boundary to Enumerable convention)
         ↓
    ConvexResultConverter.execute() runs the ConvexRel tree, converting ACell[] → Object[]
         ↓
    Calcite Avatica JDBC ResultSet
```

`INSERT`, `UPDATE` and `DELETE` plan to `ConvexTableModify` in the Enumerable convention, whose generated code calls the table's insert, update and delete methods. Operators with no CONVEX implementation, such as `UNION`, `INTERSECT` and `EXCEPT`, run in the Enumerable convention on rows converted at the boundary.

### Key Design Points

- **TranslatableTable**: `ConvexTable.toRel()` returns `ConvexTableScan` directly — Calcite never creates `EnumerableTableScan`. This ensures all queries go through our pipeline.
- **No EnumerableTableScan**: We don't implement `ScannableTable` or `FilterableTable`. The ConvexConvention pipeline handles everything.
- **DataContext threading**: `ConvexRel.execute(DataContext)` passes context through the operator tree so `ConvexFilter` can resolve PreparedStatement `?` parameters at runtime.
- **Plan reuse**: PreparedStatements reuse the plan across executions — no re-planning overhead. The plan carries its `ConvexRel` tree, stashed with `implementor.stash()`, so there is no registry. A plan is valid only for the binding of each table it reads or writes, its schema and format version (CAD039): `ConvexTableScan` and `ConvexTableModify` capture it when planned, and `ConvexTable.boundTable()` checks it before any row is read or written. Once a table is dropped or changed, execution fails with `StalePlanException` and the statement must be prepared again.
- **Type-correct output**: `ConvexToEnumerableConverter` selects SCALAR format (single column) or ARRAY format (multi-column) matching Calcite's `JavaRowFormat` contract, calling `ConvexResultConverter.executeScalar()` or `execute()` respectively.

## Components

### Convention & Interface

| Class | Purpose |
|-------|---------|
| `ConvexConvention` | Singleton convention enum (`CONVEX`) |
| `ConvexRel` | Interface: `execute(DataContext) → ConvexEnumerable` |
| `ConvexEnumerable` | `Iterable<ACell[]>` — row iterator in CVM types |

### Physical Operators

| Operator | SQL | Notes |
|----------|-----|-------|
| `ConvexTableScan` | `FROM table` | Full scan in primary key order, in one `forEach` pass over the rows |
| `ConvexFilter` | `WHERE ...` | PK pushdown for `WHERE pk = literal/param` → O(log n) `selectByKey()` |
| `ConvexProject` | `SELECT expr, ...` | Expression evaluation via `ConvexExpressionEvaluator` |
| `ConvexSort` | `ORDER BY ... LIMIT N OFFSET M` | Type-specific comparators from `SqlTypeName` at plan time |
| `ConvexAggregate` | `GROUP BY ... COUNT/SUM/AVG/MIN/MAX` | CVM-native arithmetic via `RT.plus()`, `RT.min()`, etc. |
| `ConvexMergeJoin` | `JOIN ... ON a = b` | Merge join for conditions with at least one equality (INNER, LEFT, RIGHT, FULL OUTER); sorts both inputs on the join keys, cheaply when already in key order, then merges in O(n+m); other conditions filter matched rows |
| `ConvexJoin` | `JOIN ... ON` | Nested-loop join for any condition (INNER, LEFT, RIGHT, FULL OUTER) |

### Boundary

| Class | Purpose |
|-------|---------|
| `ConvexToEnumerableConverter` | Stashes the `ConvexRel` tree in the plan and generates a call to `ConvexResultConverter` |
| `ConvexResultConverter` | Runs the `ConvexRel` tree, converting each value to the Java value for its result column's type |

### Data Modification

| Class | Purpose |
|-------|---------|
| `ConvexLogicalTableModify` | Logical `INSERT`/`UPDATE`/`DELETE` on a `ConvexTable`, from `ConvexTable.toModificationRel()` |
| `ConvexTableModify` | Enumerable implementation; generated code resolves the table, checks its binding and writes through it |

### Rules

`ConvexDriver` registers the rules through `Hook.PLANNER`. `ConvexRules` provides the query rules:

- `ConvexFilterRule` — LogicalFilter → ConvexFilter
- `ConvexProjectRule` — LogicalProject → ConvexProject
- `ConvexSortRule` — LogicalSort → ConvexSort
- `ConvexAggregateRule` — LogicalAggregate → ConvexAggregate
- `ConvexJoinRule` — LogicalJoin → ConvexJoin
- `ConvexMergeJoinRule` — LogicalJoin with an equality → ConvexMergeJoin, which the planner prefers by cost
- `ConvexToEnumerableConverterRule` — ConvexConvention → EnumerableConvention

No `ConvexTableScanRule` needed — `TranslatableTable.toRel()` creates `ConvexTableScan` directly.

For data modification, the hook replaces Calcite's `ENUMERABLE_TABLE_MODIFICATION_RULE` with:

- `ConvexTableModifyRule` — ConvexLogicalTableModify → ConvexTableModify
- `ConvexLogicalTableModifyRule` — a LogicalTableModify on a `ConvexTable`, as Calcite creates for `UPDATE` and `DELETE` → ConvexTableModify

The query rules match only `ConvexTable` plans, but the removal of Calcite's rule applies to every Calcite connection in the JVM.

### Expression Evaluator

`ConvexExpressionEvaluator` evaluates `RexNode` expressions against `ACell[]` rows:

- **Arithmetic**: +, -, *, /, MOD, negation
- **Comparison**: =, <>, <, <=, >, >=, BETWEEN, IN, IS [NOT] DISTINCT FROM
- **Logical**: AND, OR, NOT, IS NULL, IS NOT NULL
- **SEARCH**: Calcite's optimised range/equality operator (Sarg)
- **CAST**, **CASE WHEN**, **COALESCE**
- **Math**: ABS, FLOOR, CEIL, SQRT, POWER, EXP, SIGN
- **String**: UPPER, LOWER, TRIM, LTRIM, RTRIM, SUBSTRING, LENGTH, CONCAT, POSITION, REPLACE, LIKE

### PK Index Pushdown

`ConvexFilter` detects `WHERE column[0] = value` patterns (including `?` params) and uses `selectByKey()` for O(log n) index lookup instead of full scan. This is signalled to the planner via `computeSelfCost()` returning O(1) cost.

## File Structure

```
convex-db/src/main/java/convex/db/
├── calcite/
│   ├── ConvexTable.java                  # TranslatableTable + ModifiableTable, plan bindings
│   ├── ConvexSchema.java                 # Schema providing table map
│   ├── ConvexSchemaFactory.java          # Schema factory; resolves tables for DML code
│   ├── ConvexTableFactory.java           # Table factory for model config
│   ├── ConvexType.java                   # SQL ↔ CVM type mapping
│   ├── ConvexColumnType.java             # Column type with precision and scale
│   ├── ConvexTypeSystem.java             # Calcite type system for every connection
│   ├── ConvexDdlExecutor.java            # CREATE TABLE / DROP TABLE
│   ├── ConvexLogicalTableModify.java     # DML, logical
│   ├── ConvexTableModify.java            # DML, generated code
│   ├── ConvexLogicalTableModifyRule.java
│   ├── ConvexTableModifyRule.java
│   ├── StalePlanException.java           # Table changed since the plan was built
│   ├── convention/
│   │   ├── ConvexConvention.java         # Convention enum
│   │   ├── ConvexRel.java                # execute(DataContext) interface
│   │   └── ConvexEnumerable.java         # Iterable<ACell[]>
│   ├── rel/
│   │   ├── ConvexTableScan.java          # Full table scan
│   │   ├── ConvexFilter.java             # WHERE with PK pushdown
│   │   ├── ConvexProject.java            # SELECT expressions
│   │   ├── ConvexSort.java               # ORDER BY / LIMIT / OFFSET
│   │   ├── ConvexAggregate.java          # GROUP BY / aggregates
│   │   ├── ConvexMergeJoin.java          # JOIN on equality (merge)
│   │   ├── ConvexJoin.java               # JOIN on any condition (nested loop)
│   │   ├── ConvexToEnumerableConverter.java  # Convention boundary
│   │   └── ConvexResultConverter.java    # Runs the tree, ACell → Java conversion
│   ├── rules/
│   │   ├── ConvexRules.java              # Query converter rules
│   │   └── ConvexToEnumerableConverterRule.java
│   ├── eval/
│   │   └── ConvexExpressionEvaluator.java  # RexNode → ACell evaluation
│   └── pgcatalog/                        # pg_catalog tables for PostgreSQL clients
└── jdbc/
    └── ConvexDriver.java                 # JDBC driver, planner hook registration
```

## Performance

At 10,000 rows:

| Operation | Lattice API | JDBC |
|-----------|------------|------|
| PK lookup | 0.4 us/op | 5 us/op |
| Update | 1.4 us/op | 23 us/op |
| Insert | 0.9 us/op | 8 us/op |

JDBC PK lookups are O(log n) index lookups, so their cost barely grows with table size.

Planned work is tracked in GitHub issues labelled `convex-db`.
