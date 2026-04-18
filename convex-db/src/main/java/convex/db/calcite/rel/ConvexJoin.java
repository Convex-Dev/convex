package convex.db.calcite.rel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.calcite.DataContext;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexNode;

import convex.core.data.ACell;
import convex.core.data.prim.CVMBool;
import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.convention.ConvexEnumerable;
import convex.db.calcite.convention.ConvexRel;
import convex.db.calcite.eval.ConvexExpressionEvaluator;

/**
 * Join in CONVEX convention.
 *
 * <p>Performs join operations on ACell[] rows using CVM-native comparisons.
 * Supports INNER, LEFT, RIGHT, and FULL OUTER joins.
 */
public class ConvexJoin extends Join implements ConvexRel {

	public ConvexJoin(RelOptCluster cluster, RelTraitSet traitSet,
			RelNode left, RelNode right, RexNode condition,
			Set<CorrelationId> variablesSet, JoinRelType joinType) {
		super(cluster, traitSet, List.of(), left, right, condition, variablesSet, joinType);
		assert getConvention() == ConvexConvention.INSTANCE;
	}

	@Override
	public ConvexJoin copy(RelTraitSet traitSet, RexNode condition,
			RelNode left, RelNode right, JoinRelType joinType, boolean semiJoinDone) {
		return new ConvexJoin(getCluster(), traitSet, left, right, condition,
			variablesSet, joinType);
	}

	@Override
	public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
		double leftRows = mq.getRowCount(getLeft());
		double rightRows = mq.getRowCount(getRight());
		// Nested loop join cost: O(n * m)
		double joinCost = leftRows * rightRows;
		return planner.getCostFactory().makeCost(joinCost, joinCost, 0);
	}

	@Override
	public ConvexEnumerable execute(DataContext ctx) {
		ConvexRel leftRel = (ConvexRel) getLeft();
		ConvexRel rightRel = (ConvexRel) getRight();

		ConvexEnumerable leftInput = leftRel.execute(ctx);
		ConvexEnumerable rightInput = rightRel.execute(ctx);

		// Collect inputs into lists for nested loop join
		List<ACell[]> leftRows = new ArrayList<>();
		for (ACell[] row : leftInput) {
			leftRows.add(row);
		}

		List<ACell[]> rightRows = new ArrayList<>();
		for (ACell[] row : rightInput) {
			rightRows.add(row);
		}

		int leftWidth = getLeft().getRowType().getFieldCount();
		int rightWidth = getRight().getRowType().getFieldCount();
		RelDataType combinedRowType = getRowType();

		List<ACell[]> result = new ArrayList<>();

		switch (joinType) {
			case INNER -> executeInnerJoin(leftRows, rightRows, leftWidth, rightWidth, combinedRowType, result);
			case LEFT -> executeLeftJoin(leftRows, rightRows, leftWidth, rightWidth, combinedRowType, result);
			case RIGHT -> executeRightJoin(leftRows, rightRows, leftWidth, rightWidth, combinedRowType, result);
			case FULL -> executeFullJoin(leftRows, rightRows, leftWidth, rightWidth, combinedRowType, result);
			default -> throw new UnsupportedOperationException("Join type not supported: " + joinType);
		}

		return ConvexEnumerable.of(result);
	}

	/**
	 * INNER JOIN: only matching rows from both sides.
	 */
	private void executeInnerJoin(List<ACell[]> leftRows, List<ACell[]> rightRows,
			int leftWidth, int rightWidth, RelDataType rowType, List<ACell[]> result) {
		for (ACell[] leftRow : leftRows) {
			for (ACell[] rightRow : rightRows) {
				ACell[] combined = combineRows(leftRow, rightRow, leftWidth, rightWidth);
				if (matchesCondition(combined, rowType)) {
					result.add(combined);
				}
			}
		}
	}

	/**
	 * LEFT JOIN: all left rows, with matching right rows or nulls.
	 */
	private void executeLeftJoin(List<ACell[]> leftRows, List<ACell[]> rightRows,
			int leftWidth, int rightWidth, RelDataType rowType, List<ACell[]> result) {
		for (ACell[] leftRow : leftRows) {
			boolean matched = false;
			for (ACell[] rightRow : rightRows) {
				ACell[] combined = combineRows(leftRow, rightRow, leftWidth, rightWidth);
				if (matchesCondition(combined, rowType)) {
					result.add(combined);
					matched = true;
				}
			}
			if (!matched) {
				// Add left row with nulls for right side
				result.add(combineRows(leftRow, nullRow(rightWidth), leftWidth, rightWidth));
			}
		}
	}

	/**
	 * RIGHT JOIN: all right rows, with matching left rows or nulls.
	 */
	private void executeRightJoin(List<ACell[]> leftRows, List<ACell[]> rightRows,
			int leftWidth, int rightWidth, RelDataType rowType, List<ACell[]> result) {
		for (ACell[] rightRow : rightRows) {
			boolean matched = false;
			for (ACell[] leftRow : leftRows) {
				ACell[] combined = combineRows(leftRow, rightRow, leftWidth, rightWidth);
				if (matchesCondition(combined, rowType)) {
					result.add(combined);
					matched = true;
				}
			}
			if (!matched) {
				// Add null left side with right row
				result.add(combineRows(nullRow(leftWidth), rightRow, leftWidth, rightWidth));
			}
		}
	}

	/**
	 * FULL OUTER JOIN: all rows from both sides.
	 */
	private void executeFullJoin(List<ACell[]> leftRows, List<ACell[]> rightRows,
			int leftWidth, int rightWidth, RelDataType rowType, List<ACell[]> result) {
		boolean[] rightMatched = new boolean[rightRows.size()];

		for (ACell[] leftRow : leftRows) {
			boolean leftMatched = false;
			for (int i = 0; i < rightRows.size(); i++) {
				ACell[] rightRow = rightRows.get(i);
				ACell[] combined = combineRows(leftRow, rightRow, leftWidth, rightWidth);
				if (matchesCondition(combined, rowType)) {
					result.add(combined);
					leftMatched = true;
					rightMatched[i] = true;
				}
			}
			if (!leftMatched) {
				result.add(combineRows(leftRow, nullRow(rightWidth), leftWidth, rightWidth));
			}
		}

		// Add unmatched right rows
		for (int i = 0; i < rightRows.size(); i++) {
			if (!rightMatched[i]) {
				result.add(combineRows(nullRow(leftWidth), rightRows.get(i), leftWidth, rightWidth));
			}
		}
	}

	/**
	 * Combines left and right rows into a single row.
	 */
	private ACell[] combineRows(ACell[] left, ACell[] right, int leftWidth, int rightWidth) {
		ACell[] combined = new ACell[leftWidth + rightWidth];
		System.arraycopy(left, 0, combined, 0, Math.min(left.length, leftWidth));
		System.arraycopy(right, 0, combined, leftWidth, Math.min(right.length, rightWidth));
		return combined;
	}

	/**
	 * Creates a null row of the given width.
	 */
	private ACell[] nullRow(int width) {
		return new ACell[width]; // All nulls
	}

	/**
	 * Evaluates the join condition against a combined row.
	 */
	private boolean matchesCondition(ACell[] row, RelDataType rowType) {
		if (condition.isAlwaysTrue()) {
			return true;
		}
		ACell result = ConvexExpressionEvaluator.evaluate(condition, row, rowType);
		return result instanceof CVMBool b && b.booleanValue();
	}
}
