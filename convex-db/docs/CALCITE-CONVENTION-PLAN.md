# ConvexConvention Implementation Plan

## Goal

Keep CVM types (`ACell[]`) throughout the Calcite execution pipeline, only converting to Java types at the JDBC ResultSet boundary.

## Current Architecture (EnumerableConvention)

```
SQL "SELECT * FROM t WHERE id = 1"
         ↓
    Calcite Parser
         ↓
    Logical Plan (LogicalTableScan, LogicalFilter, etc.)
         ↓
    Planner converts to ENUMERABLE convention
         ↓
    EnumerableTableScan → generates Java code returning Object[]
         ↓
    ConvexEnumerator.toJava() converts ACell → Java objects
         ↓
    Calcite processes Object[] rows
         ↓
    JDBC ResultSet returns Java objects
```

**Problems:**
1. CVM → Java conversion happens early (in Enumerator)
2. Java → CVM conversion on writes (in toCell)
3. Two conversion points = overhead and potential type loss

## Target Architecture (ConvexConvention)

```
SQL "SELECT * FROM t WHERE id = 1"
         ↓
    Calcite Parser
         ↓
    Logical Plan
         ↓
    Planner converts to CONVEX convention
         ↓
    ConvexTableScan → returns ACell[] directly (no conversion)
         ↓
    ConvexFilter → operates on ACell[] with CVM comparisons
         ↓
    ConvexProject → extracts ACell columns
         ↓
    ConvexResultSet wrapper
         ↓
    ResultSet.getInt() → converts ACell → int only when called
```

**Benefits:**
1. Single conversion point (JDBC boundary)
2. CVM types preserved through execution
3. Potential for CVM-native operators (faster comparisons)
4. Better memory efficiency (no intermediate Java objects)

---

## Implementation Components

### 1. ConvexConvention

```java
package convex.db.calcite;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelTraitDef;

/**
 * Calling convention that uses ACell[] for row representation.
 */
public enum ConvexConvention implements Convention {
    INSTANCE;

    @Override
    public Class getInterface() {
        return ConvexRel.class;  // Marker interface
    }

    @Override
    public String getName() {
        return "CONVEX";
    }

    @Override
    public RelTraitDef getTraitDef() {
        return ConvexConventionTraitDef.INSTANCE;
    }

    @Override
    public boolean satisfies(RelTrait trait) {
        return this == trait;
    }

    @Override
    public boolean canConvertConvention(Convention toConvention) {
        return toConvention == this;
    }

    @Override
    public boolean useAbstractConvertersForConversion(
            RelTraitSet fromTraits, RelTraitSet toTraits) {
        return false;
    }
}
```

### 2. ConvexRel Interface

```java
package convex.db.calcite;

import convex.core.data.ACell;
import convex.core.data.AVector;
import org.apache.calcite.rel.RelNode;

/**
 * Relational expression that returns ACell[] rows.
 */
public interface ConvexRel extends RelNode {

    /**
     * Executes this relational expression and returns rows as ACell arrays.
     *
     * @param context Execution context
     * @return Iterator of ACell[] rows
     */
    ConvexEnumerable execute(ConvexExecutionContext context);
}
```

### 3. ConvexEnumerable

```java
package convex.db.calcite;

import convex.core.data.ACell;
import java.util.Iterator;

/**
 * Enumerable that yields ACell[] rows.
 */
public interface ConvexEnumerable extends Iterable<ACell[]> {

    /**
     * Returns iterator over rows.
     */
    Iterator<ACell[]> iterator();

    /**
     * Estimated row count (for optimization).
     */
    default long estimateRowCount() {
        return -1;
    }
}
```

### 4. Physical Operators

#### ConvexTableScan
```java
/**
 * Scans a ConvexTable, returning ACell[] rows directly.
 * No Java conversion - reads CVM types from lattice.
 */
public class ConvexTableScan extends TableScan implements ConvexRel {

    @Override
    public ConvexEnumerable execute(ConvexExecutionContext ctx) {
        ConvexTable table = getTable().unwrap(ConvexTable.class);
        return () -> new Iterator<ACell[]>() {
            // Iterate over LatticeTables rows
            // Return ACell[] directly - NO toJava() call
        };
    }
}
```

#### ConvexFilter
```java
/**
 * Filters rows using CVM-native comparisons.
 */
public class ConvexFilter extends Filter implements ConvexRel {

    @Override
    public ConvexEnumerable execute(ConvexExecutionContext ctx) {
        ConvexEnumerable input = ((ConvexRel) getInput()).execute(ctx);
        RexNode condition = getCondition();

        return () -> new FilteringIterator(input.iterator(), row -> {
            // Evaluate condition using CVM comparisons
            // e.g., CVMLong.compareTo() instead of Long.compare()
            return evaluateCondition(condition, row);
        });
    }
}
```

#### ConvexProject
```java
/**
 * Projects columns, keeping ACell types.
 */
public class ConvexProject extends Project implements ConvexRel {

    @Override
    public ConvexEnumerable execute(ConvexExecutionContext ctx) {
        ConvexEnumerable input = ((ConvexRel) getInput()).execute(ctx);
        List<RexNode> projects = getProjects();

        return () -> new ProjectingIterator(input.iterator(), row -> {
            ACell[] result = new ACell[projects.size()];
            for (int i = 0; i < projects.size(); i++) {
                result[i] = evaluateExpression(projects.get(i), row);
            }
            return result;
        });
    }
}
```

#### ConvexSort
```java
/**
 * Sorts using CVM Comparable implementation.
 */
public class ConvexSort extends Sort implements ConvexRel {

    @Override
    public ConvexEnumerable execute(ConvexExecutionContext ctx) {
        // Collect all rows
        List<ACell[]> rows = collectAll(((ConvexRel) getInput()).execute(ctx));

        // Sort using ACell.compareTo() - native CVM comparison
        rows.sort((a, b) -> compareRows(a, b, getCollation()));

        return () -> rows.iterator();
    }
}
```

#### ConvexAggregate
```java
/**
 * Aggregates using CVM arithmetic.
 */
public class ConvexAggregate extends Aggregate implements ConvexRel {

    @Override
    public ConvexEnumerable execute(ConvexExecutionContext ctx) {
        // Group by keys (ACell)
        // Aggregate using CVM operations:
        //   - COUNT: CVMLong.create(count)
        //   - SUM: RT.plus() for CVM addition
        //   - AVG: RT.divide() for CVM division
    }
}
```

#### ConvexTableModify
```java
/**
 * INSERT/UPDATE/DELETE with direct ACell handling.
 */
public class ConvexTableModify extends TableModify implements ConvexRel {

    @Override
    public ConvexEnumerable execute(ConvexExecutionContext ctx) {
        ConvexEnumerable input = ((ConvexRel) getInput()).execute(ctx);

        // Input is already ACell[] - no conversion needed!
        // Insert directly into LatticeTables
        for (ACell[] row : input) {
            table.insertCells(row);  // Direct ACell insert
        }
    }
}
```

### 5. Converter Rules

Rules to convert from LOGICAL to CONVEX convention:

```java
public class ConvexRules {
    public static final ConvexTableScanRule TABLE_SCAN =
        new ConvexTableScanRule();
    public static final ConvexFilterRule FILTER =
        new ConvexFilterRule();
    public static final ConvexProjectRule PROJECT =
        new ConvexProjectRule();
    public static final ConvexSortRule SORT =
        new ConvexSortRule();
    public static final ConvexAggregateRule AGGREGATE =
        new ConvexAggregateRule();
    public static final ConvexTableModifyRule TABLE_MODIFY =
        new ConvexTableModifyRule();
    public static final ConvexJoinRule JOIN =
        new ConvexJoinRule();
    public static final ConvexUnionRule UNION =
        new ConvexUnionRule();

    public static List<RelOptRule> rules() {
        return List.of(TABLE_SCAN, FILTER, PROJECT, SORT,
                       AGGREGATE, TABLE_MODIFY, JOIN, UNION);
    }
}
```

### 6. Expression Evaluator

Evaluates RexNode expressions against ACell[] rows:

```java
public class ConvexExpressionEvaluator {

    /**
     * Evaluates an expression against a row, returning ACell result.
     */
    public static ACell evaluate(RexNode expr, ACell[] row, RelDataType rowType) {
        if (expr instanceof RexInputRef ref) {
            return row[ref.getIndex()];
        }
        if (expr instanceof RexLiteral lit) {
            return literalToCell(lit);  // SQL literal → ACell
        }
        if (expr instanceof RexCall call) {
            return evaluateCall(call, row, rowType);
        }
        throw new UnsupportedOperationException("Unsupported: " + expr);
    }

    private static ACell evaluateCall(RexCall call, ACell[] row, RelDataType rowType) {
        SqlOperator op = call.getOperator();
        List<ACell> args = call.getOperands().stream()
            .map(operand -> evaluate(operand, row, rowType))
            .toList();

        // Map SQL operators to CVM operations
        return switch (op.getKind()) {
            case EQUALS -> CVMBool.create(RT.equals(args.get(0), args.get(1)));
            case NOT_EQUALS -> CVMBool.create(!RT.equals(args.get(0), args.get(1)));
            case LESS_THAN -> CVMBool.create(RT.compare(args.get(0), args.get(1)) < 0);
            case GREATER_THAN -> CVMBool.create(RT.compare(args.get(0), args.get(1)) > 0);
            case PLUS -> RT.plus(args.get(0), args.get(1));
            case MINUS -> RT.minus(args.get(0), args.get(1));
            case TIMES -> RT.times(args.get(0), args.get(1));
            case DIVIDE -> RT.divide(args.get(0), args.get(1));
            case AND -> CVMBool.create(RT.bool(args.get(0)) && RT.bool(args.get(1)));
            case OR -> CVMBool.create(RT.bool(args.get(0)) || RT.bool(args.get(1)));
            case NOT -> CVMBool.create(!RT.bool(args.get(0)));
            case IS_NULL -> CVMBool.create(args.get(0) == null);
            case IS_NOT_NULL -> CVMBool.create(args.get(0) != null);
            case CAST -> castCell(args.get(0), call.getType());
            default -> throw new UnsupportedOperationException("Operator: " + op);
        };
    }

    /**
     * Converts SQL literal to ACell at parse time.
     */
    private static ACell literalToCell(RexLiteral literal) {
        if (literal.isNull()) return null;

        return switch (literal.getTypeName()) {
            case INTEGER, BIGINT -> {
                Number n = (Number) literal.getValue();
                yield CVMLong.create(n.longValue());
            }
            case DECIMAL, DOUBLE, FLOAT -> {
                Number n = (Number) literal.getValue();
                yield CVMDouble.create(n.doubleValue());
            }
            case CHAR, VARCHAR -> {
                String s = literal.getValueAs(String.class);
                yield Strings.create(s);
            }
            case BOOLEAN -> {
                Boolean b = literal.getValueAs(Boolean.class);
                yield CVMBool.create(b);
            }
            case TIMESTAMP -> {
                Long ts = literal.getValueAs(Long.class);
                yield CVMLong.create(ts);
            }
            default -> throw new UnsupportedOperationException(
                "Literal type: " + literal.getTypeName());
        };
    }
}
```

### 7. ConvexResultSet

JDBC ResultSet wrapper that converts ACell → Java only when getter is called:

```java
public class ConvexResultSet implements ResultSet {

    private final Iterator<ACell[]> rows;
    private final ConvexColumnType[] columnTypes;
    private ACell[] currentRow;

    @Override
    public boolean next() {
        if (rows.hasNext()) {
            currentRow = rows.next();
            return true;
        }
        currentRow = null;
        return false;
    }

    @Override
    public int getInt(int columnIndex) {
        ACell cell = currentRow[columnIndex - 1];
        if (cell == null) return 0;
        if (cell instanceof CVMLong l) return (int) l.longValue();
        if (cell instanceof AInteger i) return i.intValue();
        throw new SQLException("Cannot convert to int: " + cell.getType());
    }

    @Override
    public long getLong(int columnIndex) {
        ACell cell = currentRow[columnIndex - 1];
        if (cell == null) return 0;
        if (cell instanceof CVMLong l) return l.longValue();
        if (cell instanceof AInteger i) return i.longValue();
        throw new SQLException("Cannot convert to long: " + cell.getType());
    }

    @Override
    public String getString(int columnIndex) {
        ACell cell = currentRow[columnIndex - 1];
        if (cell == null) return null;
        if (cell instanceof AString s) return s.toString();
        return cell.toString();  // Fallback: any cell to string
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) {
        ACell cell = currentRow[columnIndex - 1];
        if (cell == null) return null;
        if (cell instanceof CVMDouble d) return BigDecimal.valueOf(d.doubleValue());
        if (cell instanceof AInteger i) return new BigDecimal(i.big());
        throw new SQLException("Cannot convert to BigDecimal: " + cell.getType());
    }

    @Override
    public Object getObject(int columnIndex) {
        ACell cell = currentRow[columnIndex - 1];
        // Return ACell directly - let caller handle it
        // Or convert based on column type
        ConvexColumnType type = columnTypes[columnIndex - 1];
        return type.toJava(cell);
    }

    // ... other ResultSet methods
}
```

### 8. Integration: ConvexStatement

```java
public class ConvexStatement implements Statement {

    @Override
    public ResultSet executeQuery(String sql) {
        // 1. Parse SQL
        SqlNode sqlNode = parser.parse(sql);

        // 2. Validate
        SqlNode validated = validator.validate(sqlNode);

        // 3. Convert to RelNode
        RelRoot relRoot = converter.convertQuery(validated);

        // 4. Optimize with CONVEX convention as target
        RelNode optimized = planner.optimize(relRoot.rel,
            relRoot.rel.getTraitSet().replace(ConvexConvention.INSTANCE));

        // 5. Execute (returns ConvexEnumerable)
        ConvexRel convexRel = (ConvexRel) optimized;
        ConvexEnumerable result = convexRel.execute(context);

        // 6. Wrap in ResultSet (conversions happen here)
        return new ConvexResultSet(result.iterator(), columnTypes);
    }
}
```

---

## Implementation Phases

### Phase 1: Core Infrastructure
- [ ] `ConvexConvention` enum
- [ ] `ConvexRel` interface
- [ ] `ConvexEnumerable` interface
- [ ] `ConvexConventionTraitDef`
- [ ] Basic `ConvexExpressionEvaluator` (literals, input refs, basic ops)

### Phase 2: Table Operations
- [ ] `ConvexTableScan` - read from LatticeTables
- [ ] `ConvexTableScanRule` - LOGICAL → CONVEX
- [ ] `ConvexTableModify` - INSERT/UPDATE/DELETE
- [ ] `ConvexTableModifyRule`

### Phase 3: Query Operators
- [ ] `ConvexFilter` + rule
- [ ] `ConvexProject` + rule
- [ ] `ConvexSort` + rule
- [ ] `ConvexLimit` + rule (for LIMIT/OFFSET)

### Phase 4: Advanced Operators
- [ ] `ConvexAggregate` + rule (GROUP BY, COUNT, SUM, etc.)
- [ ] `ConvexJoin` + rule (nested loop initially)
- [ ] `ConvexUnion` + rule

### Phase 5: JDBC Integration
- [ ] `ConvexResultSet` - lazy conversion wrapper
- [ ] `ConvexStatement` - execute with CONVEX convention
- [ ] `ConvexPreparedStatement` - with parameters
- [ ] Update `ConvexDriver` to use new execution path

### Phase 6: Optimization
- [ ] Expression pushdown to scan
- [ ] Index utilization for filters
- [ ] Join ordering optimization
- [ ] Parallel scan support

---

## File Structure

```
convex-db/src/main/java/convex/db/calcite/
├── convention/
│   ├── ConvexConvention.java
│   ├── ConvexConventionTraitDef.java
│   ├── ConvexRel.java
│   └── ConvexEnumerable.java
├── rel/
│   ├── ConvexTableScan.java
│   ├── ConvexFilter.java
│   ├── ConvexProject.java
│   ├── ConvexSort.java
│   ├── ConvexAggregate.java
│   ├── ConvexJoin.java
│   ├── ConvexUnion.java
│   └── ConvexTableModify.java
├── rules/
│   ├── ConvexRules.java
│   ├── ConvexTableScanRule.java
│   ├── ConvexFilterRule.java
│   ├── ConvexProjectRule.java
│   └── ... (other rules)
├── eval/
│   └── ConvexExpressionEvaluator.java
├── jdbc/
│   ├── ConvexResultSet.java
│   ├── ConvexStatement.java
│   └── ConvexPreparedStatement.java
└── (existing files)
    ├── ConvexTable.java
    ├── ConvexSchema.java
    ├── ConvexType.java
    ├── ConvexColumnType.java
    └── ConvexDriver.java
```

---

## Comparison: Before vs After

| Aspect | EnumerableConvention | ConvexConvention |
|--------|---------------------|------------------|
| Row type | `Object[]` | `ACell[]` |
| Conversions | 2 (read + write) | 1 (JDBC only) |
| Comparison | Java `compareTo()` | CVM `RT.compare()` |
| Arithmetic | Java operators | CVM `RT.plus()` etc |
| Memory | Java objects + ACell | ACell only |
| Code generation | Yes (linq4j) | No (interpreted) |

---

## Open Questions

1. **Hybrid approach?** Keep EnumerableConvention for complex queries, use ConvexConvention for simple scans?

2. **Code generation vs interpretation?** ConvexConvention uses interpreted execution. Could add code gen later for hot paths.

3. **Index integration?** How do ConvexFilter and ConvexTableScan cooperate for index lookups?

4. **Prepared statement parameters?** How are `?` placeholders converted to ACell?

5. **NULL handling?** CVM uses `null` for null. Does this align with SQL NULL semantics everywhere?
