package convex.db.calcite.rel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.prim.CVMBool;
import convex.db.calcite.ConvexSchema;
import convex.db.calcite.ConvexTable;
import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.convention.ConvexEnumerable;
import convex.db.calcite.convention.ConvexRel;
import convex.db.calcite.eval.ConvexExpressionEvaluator;

/**
 * Filter in CONVEX convention.
 *
 * <p>Filters ACell[] rows using CVM-native comparisons.
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
		double rowCount = mq.getRowCount(this);
		return planner.getCostFactory().makeCost(rowCount, rowCount, 0);
	}

	@Override
	public ConvexEnumerable execute() {
		// Try primary key pushdown: if filtering on column[0] = literal
		// over a table scan, use selectByKey() instead of full scan
		ConvexEnumerable pushed = tryPrimaryKeyLookup();
		if (pushed != null) return pushed;

		// Fallback: full scan + filter
		ConvexRel inputRel = (ConvexRel) getInput();
		ConvexEnumerable input = inputRel.execute();

		List<ACell[]> result = new ArrayList<>();
		for (ACell[] row : input) {
			ACell evaluated = ConvexExpressionEvaluator.evaluate(condition, row, getRowType());
			if (evaluated instanceof CVMBool b && b.booleanValue()) {
				result.add(row);
			}
		}

		return ConvexEnumerable.of(result);
	}

	/**
	 * Attempts primary key pushdown. If the condition is an equality check
	 * on column 0 (the primary key) with a literal value, and the input is
	 * a table scan, uses selectByKey() for O(log n) lookup.
	 *
	 * @return ConvexEnumerable with the matching row, or null if pushdown not applicable
	 */
	private ConvexEnumerable tryPrimaryKeyLookup() {
		if (!(getInput() instanceof ConvexTableScan scan)) return null;

		ACell pkValue = extractPrimaryKeyEquality(condition);
		if (pkValue == null) return null;

		ConvexTable convexTable = scan.getTable().unwrap(ConvexTable.class);
		if (convexTable == null) return null;

		ConvexSchema schema = convexTable.getSchema();
		AVector<ACell> row = schema.getTables().selectByKey(convexTable.getTableName(), pkValue);
		if (row == null) return ConvexEnumerable.empty();

		ACell[] cells = new ACell[(int) row.count()];
		for (int i = 0; i < cells.length; i++) {
			cells[i] = row.get(i);
		}
		return ConvexEnumerable.of(Collections.singletonList(cells));
	}

	/**
	 * Extracts the primary key value from a condition of the form
	 * {@code column[0] = literal} or {@code literal = column[0]}.
	 *
	 * @return The literal value as ACell, or null if the condition doesn't match
	 */
	static ACell extractPrimaryKeyEquality(RexNode condition) {
		if (!(condition instanceof RexCall call)) return null;
		if (call.getKind() != SqlKind.EQUALS) return null;

		List<RexNode> operands = call.getOperands();
		if (operands.size() != 2) return null;

		// Try column[0] = literal
		if (operands.get(0) instanceof RexInputRef ref && ref.getIndex() == 0
				&& operands.get(1) instanceof RexLiteral lit) {
			return ConvexExpressionEvaluator.literalToCell(lit);
		}
		// Try literal = column[0]
		if (operands.get(1) instanceof RexInputRef ref && ref.getIndex() == 0
				&& operands.get(0) instanceof RexLiteral lit) {
			return ConvexExpressionEvaluator.literalToCell(lit);
		}

		return null;
	}
}
