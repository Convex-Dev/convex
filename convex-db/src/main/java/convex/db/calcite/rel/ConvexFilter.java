package convex.db.calcite.rel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.calcite.DataContext;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.prim.CVMBool;
import convex.db.calcite.ConvexSchema;
import convex.db.calcite.ConvexTable;
import convex.db.calcite.ConvexType;
import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.convention.ConvexEnumerable;
import convex.db.calcite.convention.ConvexRel;
import convex.db.calcite.eval.ConvexExpressionEvaluator;

/**
 * Filter in CONVEX convention.
 *
 * <p>Filters ACell[] rows using CVM-native comparisons. When the filter
 * is a primary key equality ({@code WHERE id = literal} or {@code WHERE id = ?param})
 * over a ConvexTableScan, the filter is pushed down to {@code selectByKey()}
 * for O(log n) index lookup instead of a full table scan.
 *
 * <p>Dynamic parameters from PreparedStatements are resolved via the
 * DataContext passed through the ConvexRel execution pipeline.
 */
public class ConvexFilter extends Filter implements ConvexRel {

	public ConvexFilter(RelOptCluster cluster, RelTraitSet traitSet,
			RelNode input, RexNode condition) {
		super(cluster, traitSet, input, condition);
		assert getConvention() == ConvexConvention.INSTANCE;
	}

	@Override
	public ConvexFilter copy(RelTraitSet traitSet, RelNode input, RexNode condition) {
		return new ConvexFilter(getCluster(), traitSet, input, condition);
	}

	@Override
	public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
		if (canPushdownPrimaryKey()) {
			// PK pushdown: O(log n) index lookup, returns at most 1 row
			return planner.getCostFactory().makeCost(1, 1, 0);
		}
		double rowCount = mq.getRowCount(this);
		return planner.getCostFactory().makeCost(rowCount, rowCount, 0);
	}

	/**
	 * Returns true if this filter can be resolved via primary key index lookup.
	 */
	private boolean canPushdownPrimaryKey() {
		if (!(getInput() instanceof ConvexTableScan)) return false;
		return hasPrimaryKeyEquality(condition);
	}

	/**
	 * Checks if a condition contains a primary key equality (column[0] = value/param).
	 */
	private static boolean hasPrimaryKeyEquality(RexNode condition) {
		if (!(condition instanceof RexCall call)) return false;
		if (call.getKind() != SqlKind.EQUALS) return false;
		var operands = call.getOperands();
		if (operands.size() != 2) return false;
		if (operands.get(0) instanceof RexInputRef ref && ref.getIndex() == 0) {
			return operands.get(1) instanceof RexLiteral || operands.get(1) instanceof RexDynamicParam;
		}
		if (operands.get(1) instanceof RexInputRef ref && ref.getIndex() == 0) {
			return operands.get(0) instanceof RexLiteral || operands.get(0) instanceof RexDynamicParam;
		}
		return false;
	}

	@Override
	public ConvexEnumerable execute(DataContext ctx) {
		// Try primary key pushdown: if filtering on column[0] = literal/param
		// over a table scan, use selectByKey() instead of full scan
		ConvexEnumerable pushed = tryPrimaryKeyLookup(ctx);
		if (pushed != null) return pushed;

		// Fallback: full scan + filter
		ConvexRel inputRel = (ConvexRel) getInput();
		ConvexEnumerable input = inputRel.execute(ctx);

		List<ACell[]> result = new ArrayList<>();
		for (ACell[] row : input) {
			ACell evaluated = ConvexExpressionEvaluator.evaluate(condition, row, getRowType(), ctx);
			if (evaluated instanceof CVMBool b && b.booleanValue()) {
				result.add(row);
			}
		}

		return ConvexEnumerable.of(result);
	}

	/**
	 * Attempts primary key pushdown. If the condition is an equality check
	 * on column 0 (the primary key) with a literal or bound parameter value,
	 * and the input is a table scan, uses selectByKey() for O(log n) lookup.
	 *
	 * @param ctx DataContext for resolving dynamic parameters (may be null)
	 * @return ConvexEnumerable with the matching row, or null if pushdown not applicable
	 */
	private ConvexEnumerable tryPrimaryKeyLookup(DataContext ctx) {
		if (!(getInput() instanceof ConvexTableScan scan)) return null;

		ACell pkValue = extractPrimaryKeyEquality(condition, ctx);
		if (pkValue == null) return null;

		ConvexTable convexTable = scan.getTable().unwrap(ConvexTable.class);
		if (convexTable == null) return null;

		ConvexSchema schema = convexTable.getSchema();
		AVector<ACell> row = schema.getTables().selectByKey(convexTable.getTableName(), pkValue);
		if (row == null) return ConvexEnumerable.empty();

		return ConvexEnumerable.of(Collections.singletonList(row.toCellArray()));
	}

	/**
	 * Extracts the primary key value from a condition of the form
	 * {@code column[0] = literal} or {@code column[0] = ?param}.
	 *
	 * @param condition Filter condition
	 * @param ctx DataContext for resolving dynamic parameters
	 * @return The value as ACell, or null if the condition doesn't match
	 */
	static ACell extractPrimaryKeyEquality(RexNode condition, DataContext ctx) {
		if (!(condition instanceof RexCall call)) return null;
		if (call.getKind() != SqlKind.EQUALS) return null;

		List<RexNode> operands = call.getOperands();
		if (operands.size() != 2) return null;

		// Try column[0] = value
		if (operands.get(0) instanceof RexInputRef ref && ref.getIndex() == 0) {
			ACell val = resolveValue(operands.get(1), ctx);
			if (val != null) return val;
		}
		// Try value = column[0]
		if (operands.get(1) instanceof RexInputRef ref && ref.getIndex() == 0) {
			ACell val = resolveValue(operands.get(0), ctx);
			if (val != null) return val;
		}

		return null;
	}

	/**
	 * Resolves a RexNode to an ACell value. Handles literals and dynamic params.
	 */
	private static ACell resolveValue(RexNode node, DataContext ctx) {
		if (node instanceof RexLiteral lit) {
			return ConvexExpressionEvaluator.literalToCell(lit);
		}
		if (node instanceof RexDynamicParam param && ctx != null) {
			Object val = ctx.get("?" + param.getIndex());
			if (val != null) {
				return ConvexType.ANY.toCell(val);
			}
		}
		return null;
	}
}
