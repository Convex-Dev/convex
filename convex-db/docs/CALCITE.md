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
    ConvexRelExecutor.execute() converts ACell[] → Object[] at runtime
         ↓
    Calcite Avatica JDBC ResultSet
```

### Key Design Points

- **TranslatableTable**: `ConvexTable.toRel()` returns `ConvexTableScan` directly — Calcite never creates `EnumerableTableScan`. This ensures all queries go through our pipeline.
- **No EnumerableTableScan**: We don't implement `ScannableTable` or `FilterableTable`. The ConvexConvention pipeline handles everything.
- **DataContext threading**: `ConvexRel.execute(DataContext)` passes context through the operator tree so `ConvexFilter` can resolve PreparedStatement `?` parameters at runtime.
- **Plan reuse**: `ConvexRelExecutor` registers plans once at compile time. PreparedStatements reuse the plan across executions — no re-planning overhead.
- **Type-correct output**: `ConvexToEnumerableConverter` selects SCALAR format (single column) or ARRAY format (multi-column) matching Calcite's `JavaRowFormat` contract, calling `executeScalar()` or `execute()` respectively.

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
| `ConvexTableScan` | `FROM table` | Full scan via `selectAll()` |
| `ConvexFilter` | `WHERE ...` | PK pushdown for `WHERE pk = literal/param` → O(log n) `selectByKey()` |
| `ConvexProject` | `SELECT expr, ...` | Expression evaluation via `ConvexExpressionEvaluator` |
| `ConvexSort` | `ORDER BY ... LIMIT N OFFSET M` | Type-specific comparators from `SqlTypeName` at plan time |
| `ConvexAggregate` | `GROUP BY ... COUNT/SUM/AVG/MIN/MAX` | CVM-native arithmetic via `RT.plus()`, `RT.min()`, etc. |
| `ConvexJoin` | `JOIN ... ON` | Nested-loop join (INNER, LEFT, RIGHT, FULL OUTER) |

### Boundary

| Class | Purpose |
|-------|---------|
| `ConvexToEnumerableConverter` | Generates code calling `ConvexRelExecutor` at runtime |
| `ConvexRelExecutor` | Executes ConvexRel tree, converts `ACell[]` → `Object[]` |

### Rules

`ConvexRules` provides converter rules registered via `Hook.PLANNER` in `ConvexDriver`:

- `ConvexFilterRule` — LogicalFilter → ConvexFilter
- `ConvexProjectRule` — LogicalProject → ConvexProject
- `ConvexSortRule` — LogicalSort → ConvexSort
- `ConvexAggregateRule` — LogicalAggregate → ConvexAggregate
- `ConvexJoinRule` — LogicalJoin → ConvexJoin
- `ConvexToEnumerableConverterRule` — ConvexConvention → EnumerableConvention

No `ConvexTableScanRule` needed — `TranslatableTable.toRel()` creates `ConvexTableScan` directly.

### Expression Evaluator

`ConvexExpressionEvaluator` evaluates `RexNode` expressions against `ACell[]` rows:

- **Arithmetic**: +, -, *, /, MOD, negation
- **Comparison**: =, <>, <, <=, >, >=, BETWEEN, IN
- **Logical**: AND, OR, NOT, IS NULL, IS NOT NULL
- **SEARCH**: Calcite's optimised range/equality operator (Sarg)
- **CAST**, **CASE WHEN**, **COALESCE**
- **Math**: ABS, FLOOR, CEIL, SQRT, POWER, EXP, SIGN
- **String**: UPPER, LOWER, TRIM, SUBSTRING, LENGTH, CONCAT, POSITION, REPLACE, LIKE

### PK Index Pushdown

`ConvexFilter` detects `WHERE column[0] = value` patterns (including `?` params) and uses `selectByKey()` for O(log n) index lookup instead of full scan. This is signalled to the planner via `computeSelfCost()` returning O(1) cost.

## File Structure

```
convex-db/src/main/java/convex/db/calcite/
├── ConvexTable.java              # TranslatableTable + ModifiableTable
├── ConvexSchema.java             # Schema providing table map
├── ConvexSchemaFactory.java      # Schema factory for model config
├── ConvexType.java               # SQL ↔ CVM type mapping
├── ConvexColumnType.java         # Column type with nullability
├── ConvexDdlExecutor.java        # CREATE TABLE / DROP TABLE
├── convention/
│   ├── ConvexConvention.java     # Convention enum
│   ├── ConvexRel.java            # execute(DataContext) interface
│   └── ConvexEnumerable.java     # Iterable<ACell[]>
├── rel/
│   ├── ConvexTableScan.java      # Full table scan
│   ├── ConvexFilter.java         # WHERE with PK pushdown
│   ├── ConvexProject.java        # SELECT expressions
│   ├── ConvexSort.java           # ORDER BY / LIMIT / OFFSET
│   ├── ConvexAggregate.java      # GROUP BY / aggregates
│   ├── ConvexJoin.java           # JOIN (nested loop)
│   ├── ConvexToEnumerableConverter.java  # Convention boundary
│   └── ConvexRelExecutor.java    # Runtime executor + ACell→Java conversion
├── rules/
│   ├── ConvexRules.java          # All converter rules
│   └── ConvexToEnumerableConverterRule.java
├── eval/
│   └── ConvexExpressionEvaluator.java  # RexNode → ACell evaluation
└── jdbc/
    └── ConvexDriver.java         # JDBC driver, planner hook registration
```

## Performance

At 10,000 rows:

| Operation | Lattice API | JDBC |
|-----------|------------|------|
| PK lookup | 0.4 us/op | 5 us/op |
| Update | 1.4 us/op | 23 us/op |
| Insert | 0.9 us/op | 8 us/op |

JDBC PK lookups are constant time (O(log n)) regardless of table size.

## Open Questions

1. **Hash join**: Current join is nested-loop O(n*m). Hash join would improve equi-join performance.
2. **Query plan caching**: Plans are reused per PreparedStatement but not across statements with the same SQL text.
3. **Secondary indexes**: Only PK (column 0) pushdown is supported. See issue #537.
4. **Window functions**: Not yet implemented.
