# Convex Calcite SQL Adapter — Implementation Status

## Implemented

### ConvexConvention Pipeline (CVM-native types throughout)
- [x] TranslatableTable — ConvexTable.toRel() returns ConvexTableScan directly
- [x] ConvexTableScan — full table scan via lattice Index
- [x] ConvexFilter — WHERE clause with PK index pushdown (literals + PreparedStatement params)
- [x] ConvexProject — SELECT expressions
- [x] ConvexSort — ORDER BY with type-specific comparators, LIMIT, OFFSET
- [x] ConvexAggregate — GROUP BY, COUNT, SUM, AVG, MIN, MAX, HAVING
- [x] ConvexJoin — INNER, LEFT, RIGHT, FULL OUTER (nested loop)
- [x] DML — INSERT, UPDATE, DELETE (via EnumerableConvention)
- [x] DDL — CREATE TABLE, DROP TABLE
- [x] DataContext threading — PreparedStatement `?` param resolution
- [x] Plan reuse — ConvexRelExecutor registry for PreparedStatement caching
- [x] SCALAR/ARRAY format — correct JavaRowFormat for single vs multi-column results

### Expression Evaluator
- [x] Arithmetic: +, -, *, /, MOD, negation
- [x] Comparisons: =, <>, <, <=, >, >=, BETWEEN, IN
- [x] SEARCH — Calcite's Sarg ranges for AND/OR/IN optimisation
- [x] NULL-safe: IS_NOT_DISTINCT_FROM, IS_DISTINCT_FROM
- [x] Logical: AND, OR, NOT, IS NULL, IS NOT NULL
- [x] Conditionals: CASE WHEN, COALESCE
- [x] CAST
- [x] Math: ABS, FLOOR, CEIL, SQRT, POWER, EXP, SIGN
- [x] String: UPPER, LOWER, TRIM, SUBSTRING, LENGTH, CONCAT, POSITION, REPLACE, LIKE

---

## Phase 2: Set Operations

- [ ] ConvexUnion — UNION, UNION ALL
- [ ] ConvexIntersect — INTERSECT, INTERSECT ALL
- [ ] ConvexMinus — EXCEPT, EXCEPT ALL

## Phase 3: Advanced Aggregates

- [ ] STDDEV, STDDEV_POP, STDDEV_SAMP, VARIANCE
- [ ] Window functions (ROW_NUMBER, RANK, LAG, LEAD, running totals)
- [ ] STRING_AGG / LISTAGG

## Phase 4: Additional Functions

- [ ] NULLIF, NVL/IFNULL
- [ ] Date/time functions (depends on CVM date type support)
- [ ] LPAD, RPAD, REVERSE, REPEAT, INITCAP
- [ ] REGEXP_LIKE, REGEXP_REPLACE

## Phase 5: Optimisation

- [ ] Secondary index support (issue #537)
- [ ] Hash join for equi-joins
- [ ] Merge join for sorted inputs
- [ ] Cross-statement query plan caching
- [ ] Table statistics for cost estimation
- [ ] Parallel scan support

---

## Notes

### CVM Type Gaps
- LTRIM, RTRIM use Java String fallback (only TRIM exists in AString)
- Case-insensitive operations use UPPER/LOWER conversion

### Testing Approach
Each feature includes:
1. Unit tests for the operator class
2. Integration tests via JDBC (JoinSubqueryTest, SortAggregateTest, etc.)
3. Edge case tests (NULL handling, empty results)
4. KeyLookupBench for performance regression testing
