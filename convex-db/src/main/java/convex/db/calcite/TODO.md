# Convex Calcite SQL Adapter - Implementation TODOs

## Status Summary

### Implemented (ConvexConvention - pure CVM types)
- [x] ConvexTableScan - table scanning
- [x] ConvexFilter - WHERE clause filtering
- [x] ConvexProject - SELECT expressions
- [x] ConvexSort - ORDER BY, LIMIT, OFFSET
- [x] ConvexAggregate - GROUP BY, COUNT, SUM, AVG, MIN, MAX
- [x] ConvexJoin - INNER, LEFT, RIGHT, FULL OUTER joins
- [x] DML - INSERT, UPDATE, DELETE (via EnumerableConvention)

### Expression Evaluator
- [x] Arithmetic: +, -, *, /, MOD, negation
- [x] Comparisons: =, <>, <, <=, >, >=, BETWEEN, IN
- [x] NULL-safe: IS_NOT_DISTINCT_FROM, IS_DISTINCT_FROM
- [x] Logical: AND, OR, NOT, IS NULL, IS NOT NULL
- [x] Conditionals: CASE WHEN, COALESCE
- [x] CAST
- [x] Math: ABS, FLOOR, CEIL, SQRT, POWER, EXP, SIGN
- [x] String: UPPER, LOWER, TRIM, SUBSTRING, LENGTH, CONCAT, POSITION, REPLACE, LIKE

---

## Phase 2: Set Operations

### TODO: ConvexUnion
- [ ] Create `ConvexUnion` class implementing `ConvexRel`
- [ ] Support UNION ALL (append results)
- [ ] Support UNION (deduplicate results)
- [ ] Add `ConvexUnionRule` to convert `LogicalUnion`
- [ ] Add tests for UNION operations

### TODO: ConvexIntersect
- [ ] Create `ConvexIntersect` class implementing `ConvexRel`
- [ ] Support INTERSECT (rows in both sets)
- [ ] Support INTERSECT ALL
- [ ] Add `ConvexIntersectRule` to convert `LogicalIntersect`
- [ ] Add tests

### TODO: ConvexMinus (EXCEPT)
- [ ] Create `ConvexMinus` class implementing `ConvexRel`
- [ ] Support EXCEPT (rows in first but not second)
- [ ] Support EXCEPT ALL
- [ ] Add `ConvexMinusRule` to convert `LogicalMinus`
- [ ] Add tests

---

## Phase 3: Advanced Aggregates

### TODO: Additional Aggregate Functions
- [ ] STDDEV, STDDEV_POP, STDDEV_SAMP
- [ ] VARIANCE, VAR_POP, VAR_SAMP
- [ ] FIRST_VALUE, LAST_VALUE (may need ordering)
- [ ] STRING_AGG / LISTAGG (string concatenation)
- [ ] BIT_AND, BIT_OR, BIT_XOR

### TODO: Window Functions
- [ ] Create `ConvexWindow` class implementing `ConvexRel`
- [ ] Support OVER clause parsing
- [ ] ROW_NUMBER()
- [ ] RANK(), DENSE_RANK()
- [ ] LAG(), LEAD()
- [ ] FIRST_VALUE(), LAST_VALUE() with OVER
- [ ] SUM/AVG/COUNT with OVER (running totals)
- [ ] Add `ConvexWindowRule`
- [ ] Add comprehensive tests

---

## Phase 4: Additional Functions

### TODO: NULL Functions
- [ ] NULLIF(a, b) - returns NULL if a = b
- [ ] NVL(a, b) / IFNULL(a, b) - return b if a is NULL
- [ ] NVL2(a, b, c) - return b if a not null, else c

### TODO: Date/Time Functions
- [ ] Depends on CVM date/time type support
- [ ] CURRENT_DATE, CURRENT_TIME, CURRENT_TIMESTAMP
- [ ] DATE_ADD, DATE_SUB
- [ ] EXTRACT(part FROM date)
- [ ] DATE_TRUNC
- [ ] TO_DATE, TO_TIMESTAMP parsing

### TODO: Additional String Functions
- [ ] LPAD, RPAD - padding
- [ ] REVERSE
- [ ] REPEAT
- [ ] LEFT, RIGHT
- [ ] INITCAP
- [ ] REGEXP_LIKE, REGEXP_REPLACE (if regex support desired)

### TODO: Type Functions
- [ ] TYPEOF / DATATYPE
- [ ] COERCE (explicit type coercion)

---

## Phase 5: Optimisation & Advanced Features

### TODO: Index Support
- [ ] Push predicates to index lookup
- [ ] Use indexes for ORDER BY
- [ ] Index statistics for cost estimation
- [ ] Implement `FilterableTable` / `ProjectableFilterableTable`

### TODO: Prepared Statements
- [ ] Support `?` parameter placeholders
- [ ] Parameter binding via PreparedStatement
- [ ] Type inference for parameters

### TODO: Query Plan Caching
- [ ] Cache compiled query plans
- [ ] Invalidation on schema changes
- [ ] Statistics-based plan selection

### TODO: Additional Join Algorithms
- [ ] Hash join for equi-joins
- [ ] Merge join for sorted inputs
- [ ] Semi-join optimisation for EXISTS
- [ ] Anti-join optimisation for NOT EXISTS

### TODO: Cost Model Improvements
- [ ] Table statistics (row count, column cardinality)
- [ ] Histogram-based selectivity estimation
- [ ] Index awareness in cost model

---

## Notes

### CVM Type Gaps
The following SQL operations require Java String fallbacks since CVM equivalents don't exist:
- LTRIM, RTRIM (only TRIM exists in AString)
- Case-insensitive operations (except UPPER/LOWER conversion)

Consider adding to convex-core:
- `AString.trimLeft()`, `AString.trimRight()`
- `AString.indexOf(AString needle)`

### Testing Approach
Each new feature should include:
1. Unit tests for the operator class
2. Integration tests via JDBC
3. Edge case tests (NULL handling, empty results, etc.)

### Performance Considerations
Current implementation uses nested-loop join (O(n*m)). For large tables, consider:
- Hash join implementation
- Sort-merge join for pre-sorted data
- Index-based lookup for selective conditions
