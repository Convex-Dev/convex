package convex.db.calcite.rel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
import org.apache.calcite.rel.core.JoinInfo;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidatorUtil;

import convex.core.data.ACell;
import convex.core.data.prim.CVMBool;
import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.convention.ConvexEnumerable;
import convex.db.calcite.convention.ConvexRel;
import convex.db.calcite.eval.ConvexExpressionEvaluator;

/**
 * Merge join in CONVEX convention.
 *
 * <p>Collects both inputs and sorts each on the equi-join key columns, then
 * merges them in O(n+m). Sorting costs little when an input is already in
 * key order, as it is when the join key is the primary key (column 0) — the
 * Index radix tree produces rows in sorted PK order.
 *
 * <p>Supports INNER, LEFT, RIGHT and FULL OUTER joins, and SEMI and ANTI
 * joins (the left rows with a match on the right, or with none). Any residual
 * non-equi conditions are evaluated as a post-filter on matched rows.
 *
 * <p>A row with NULL in a key compared by {@code =} matches nothing, since
 * {@code NULL = x} is not true; a key compared by {@code IS NOT DISTINCT FROM}
 * does match NULL with NULL. The merge itself orders NULL keys together, so
 * rows with a NULL in an excluding key are set apart before it and added back
 * afterwards where the join type keeps unmatched rows.
 */
public class ConvexMergeJoin extends Join implements ConvexRel {

	public ConvexMergeJoin(RelOptCluster cluster, RelTraitSet traitSet,
			RelNode left, RelNode right, RexNode condition,
			Set<CorrelationId> variablesSet, JoinRelType joinType) {
		super(cluster, traitSet, List.of(), left, right, condition, variablesSet, joinType);
		assert getConvention() == ConvexConvention.INSTANCE;
	}

	@Override
	public ConvexMergeJoin copy(RelTraitSet traitSet, RexNode condition,
			RelNode left, RelNode right, JoinRelType joinType, boolean semiJoinDone) {
		return new ConvexMergeJoin(getCluster(), traitSet, left, right, condition,
			variablesSet, joinType);
	}

	@Override
	public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
		double leftRows = mq.getRowCount(getLeft());
		double rightRows = mq.getRowCount(getRight());
		// Merge join cost: O(n + m)
		double cost = leftRows + rightRows;
		return planner.getCostFactory().makeCost(cost, cost, 0);
	}

	@Override
	public ConvexEnumerable execute(DataContext ctx) {
		ConvexRel leftRel = (ConvexRel) getLeft();
		ConvexRel rightRel = (ConvexRel) getRight();

		List<ACell[]> leftRows = collect(leftRel.execute(ctx));
		List<ACell[]> rightRows = collect(rightRel.execute(ctx));

		JoinInfo info = analyzeCondition();
		int[] leftKeys = info.leftKeys.toIntArray();
		int[] rightKeys = info.rightKeys.toIntArray();

		// Build comparator from the equi-join key types
		Comparator<ACell> keyComparator = buildKeyComparator(leftKeys);

		int leftWidth = getLeft().getRowType().getFieldCount();
		int rightWidth = getRight().getRowType().getFieldCount();
		// The residual condition is evaluated over a left row and a right row side by side.
		// For SEMI and ANTI that is not this join's own row type, which is the left row alone.
		RelDataType combinedType = joinType.projectsRight() ? getRowType()
			: SqlValidatorUtil.createJoinType(getCluster().getTypeFactory(),
				getLeft().getRowType(), getRight().getRowType(), null, List.of());
		List<RexNode> residual = info.nonEquiConditions;

		// Rows with NULL in a key compared by "=" match nothing; see the class comment
		boolean[] nullsExcluded = new boolean[leftKeys.length];
		for (int i = 0; i < nullsExcluded.length; i++) {
			nullsExcluded[i] = i >= info.nullExclusionFlags.size() || info.nullExclusionFlags.get(i);
		}
		List<ACell[]> leftNoMatch = setApartNullKeys(leftRows, leftKeys, nullsExcluded);
		List<ACell[]> rightNoMatch = setApartNullKeys(rightRows, rightKeys, nullsExcluded);

		// Sort both sides by join key (no-op if already sorted, e.g. PK scan)
		Comparator<ACell[]> leftComp = rowComparator(leftKeys, keyComparator);
		Comparator<ACell[]> rightComp = rowComparator(rightKeys, keyComparator);
		leftRows.sort(leftComp);
		rightRows.sort(rightComp);

		List<ACell[]> result = new ArrayList<>();

		switch (joinType) {
			case INNER -> mergeInner(leftRows, rightRows, leftKeys, rightKeys,
				keyComparator, leftWidth, rightWidth, combinedType, residual, result);
			case LEFT -> mergeLeft(leftRows, rightRows, leftKeys, rightKeys,
				keyComparator, leftWidth, rightWidth, combinedType, residual, result);
			case RIGHT -> mergeRight(leftRows, rightRows, leftKeys, rightKeys,
				keyComparator, leftWidth, rightWidth, combinedType, residual, result);
			case FULL -> mergeFull(leftRows, rightRows, leftKeys, rightKeys,
				keyComparator, leftWidth, rightWidth, combinedType, residual, result);
			case SEMI -> mergeSemi(leftRows, rightRows, leftKeys, rightKeys,
				keyComparator, leftWidth, rightWidth, combinedType, residual, false, result);
			case ANTI -> mergeSemi(leftRows, rightRows, leftKeys, rightKeys,
				keyComparator, leftWidth, rightWidth, combinedType, residual, true, result);
			default -> throw new UnsupportedOperationException("Join type not supported: " + joinType);
		}

		// The rows set apart could match nothing; the join types that keep unmatched rows get them back
		if (joinType == JoinRelType.LEFT || joinType == JoinRelType.FULL) {
			for (ACell[] row : leftNoMatch) result.add(combineRows(row, nullRow(rightWidth), leftWidth, rightWidth));
		}
		if (joinType == JoinRelType.RIGHT || joinType == JoinRelType.FULL) {
			for (ACell[] row : rightNoMatch) result.add(combineRows(nullRow(leftWidth), row, leftWidth, rightWidth));
		}
		if (joinType == JoinRelType.ANTI) {
			for (ACell[] row : leftNoMatch) result.add(Arrays.copyOf(row, leftWidth));
		}

		return ConvexEnumerable.of(result);
	}

	// ========== Merge Algorithms ==========

	private void mergeInner(List<ACell[]> left, List<ACell[]> right,
			int[] leftKeys, int[] rightKeys, Comparator<ACell> keyCmp,
			int lw, int rw, RelDataType rowType, List<RexNode> residual,
			List<ACell[]> result) {
		int li = 0, ri = 0;
		while (li < left.size() && ri < right.size()) {
			int cmp = compareKeys(left.get(li), leftKeys, right.get(ri), rightKeys, keyCmp);
			if (cmp < 0) {
				li++;
			} else if (cmp > 0) {
				ri++;
			} else {
				// Keys match — find extent of matching group on both sides
				int ls = li, rs = ri;
				while (li < left.size() && compareKeys(left.get(li), leftKeys, left.get(ls), leftKeys, keyCmp) == 0) li++;
				while (ri < right.size() && compareKeys(right.get(ri), rightKeys, right.get(rs), rightKeys, keyCmp) == 0) ri++;
				// Cross-product within the matching group
				for (int l = ls; l < li; l++) {
					for (int r = rs; r < ri; r++) {
						ACell[] combined = combineRows(left.get(l), right.get(r), lw, rw);
						if (matchesResidual(combined, rowType, residual)) {
							result.add(combined);
						}
					}
				}
			}
		}
	}

	private void mergeLeft(List<ACell[]> left, List<ACell[]> right,
			int[] leftKeys, int[] rightKeys, Comparator<ACell> keyCmp,
			int lw, int rw, RelDataType rowType, List<RexNode> residual,
			List<ACell[]> result) {
		int li = 0, ri = 0;
		while (li < left.size()) {
			if (ri >= right.size()) {
				result.add(combineRows(left.get(li++), nullRow(rw), lw, rw));
				continue;
			}
			int cmp = compareKeys(left.get(li), leftKeys, right.get(ri), rightKeys, keyCmp);
			if (cmp < 0) {
				result.add(combineRows(left.get(li++), nullRow(rw), lw, rw));
			} else if (cmp > 0) {
				ri++;
			} else {
				int ls = li, rs = ri;
				while (li < left.size() && compareKeys(left.get(li), leftKeys, left.get(ls), leftKeys, keyCmp) == 0) li++;
				while (ri < right.size() && compareKeys(right.get(ri), rightKeys, right.get(rs), rightKeys, keyCmp) == 0) ri++;
				for (int l = ls; l < li; l++) {
					boolean matched = false;
					for (int r = rs; r < ri; r++) {
						ACell[] combined = combineRows(left.get(l), right.get(r), lw, rw);
						if (matchesResidual(combined, rowType, residual)) {
							result.add(combined);
							matched = true;
						}
					}
					if (!matched) {
						result.add(combineRows(left.get(l), nullRow(rw), lw, rw));
					}
				}
			}
		}
	}

	private void mergeRight(List<ACell[]> left, List<ACell[]> right,
			int[] leftKeys, int[] rightKeys, Comparator<ACell> keyCmp,
			int lw, int rw, RelDataType rowType, List<RexNode> residual,
			List<ACell[]> result) {
		int li = 0, ri = 0;
		while (ri < right.size()) {
			if (li >= left.size()) {
				result.add(combineRows(nullRow(lw), right.get(ri++), lw, rw));
				continue;
			}
			int cmp = compareKeys(left.get(li), leftKeys, right.get(ri), rightKeys, keyCmp);
			if (cmp < 0) {
				li++;
			} else if (cmp > 0) {
				result.add(combineRows(nullRow(lw), right.get(ri++), lw, rw));
			} else {
				int ls = li, rs = ri;
				while (li < left.size() && compareKeys(left.get(li), leftKeys, left.get(ls), leftKeys, keyCmp) == 0) li++;
				while (ri < right.size() && compareKeys(right.get(ri), rightKeys, right.get(rs), rightKeys, keyCmp) == 0) ri++;
				for (int r = rs; r < ri; r++) {
					boolean matched = false;
					for (int l = ls; l < li; l++) {
						ACell[] combined = combineRows(left.get(l), right.get(r), lw, rw);
						if (matchesResidual(combined, rowType, residual)) {
							result.add(combined);
							matched = true;
						}
					}
					if (!matched) {
						result.add(combineRows(nullRow(lw), right.get(r), lw, rw));
					}
				}
			}
		}
	}

	private void mergeFull(List<ACell[]> left, List<ACell[]> right,
			int[] leftKeys, int[] rightKeys, Comparator<ACell> keyCmp,
			int lw, int rw, RelDataType rowType, List<RexNode> residual,
			List<ACell[]> result) {
		int li = 0, ri = 0;
		while (li < left.size() && ri < right.size()) {
			int cmp = compareKeys(left.get(li), leftKeys, right.get(ri), rightKeys, keyCmp);
			if (cmp < 0) {
				result.add(combineRows(left.get(li++), nullRow(rw), lw, rw));
			} else if (cmp > 0) {
				result.add(combineRows(nullRow(lw), right.get(ri++), lw, rw));
			} else {
				int ls = li, rs = ri;
				while (li < left.size() && compareKeys(left.get(li), leftKeys, left.get(ls), leftKeys, keyCmp) == 0) li++;
				while (ri < right.size() && compareKeys(right.get(ri), rightKeys, right.get(rs), rightKeys, keyCmp) == 0) ri++;
				boolean[] rightUsed = new boolean[ri - rs];
				for (int l = ls; l < li; l++) {
					boolean leftMatched = false;
					for (int r = rs; r < ri; r++) {
						ACell[] combined = combineRows(left.get(l), right.get(r), lw, rw);
						if (matchesResidual(combined, rowType, residual)) {
							result.add(combined);
							leftMatched = true;
							rightUsed[r - rs] = true;
						}
					}
					if (!leftMatched) {
						result.add(combineRows(left.get(l), nullRow(rw), lw, rw));
					}
				}
				for (int r = rs; r < ri; r++) {
					if (!rightUsed[r - rs]) {
						result.add(combineRows(nullRow(lw), right.get(r), lw, rw));
					}
				}
			}
		}
		while (li < left.size()) {
			result.add(combineRows(left.get(li++), nullRow(rw), lw, rw));
		}
		while (ri < right.size()) {
			result.add(combineRows(nullRow(lw), right.get(ri++), lw, rw));
		}
	}

	/**
	 * SEMI: each left row that matches at least one right row, once, as the left row
	 * alone. ANTI ({@code anti}): each left row that matches none.
	 */
	private void mergeSemi(List<ACell[]> left, List<ACell[]> right,
			int[] leftKeys, int[] rightKeys, Comparator<ACell> keyCmp,
			int lw, int rw, RelDataType rowType, List<RexNode> residual,
			boolean anti, List<ACell[]> result) {
		int li = 0, ri = 0;
		while (li < left.size()) {
			int cmp = ri >= right.size() ? -1
				: compareKeys(left.get(li), leftKeys, right.get(ri), rightKeys, keyCmp);
			if (cmp < 0) {
				if (anti) result.add(Arrays.copyOf(left.get(li), lw));
				li++;
			} else if (cmp > 0) {
				ri++;
			} else {
				int ls = li, rs = ri;
				while (li < left.size() && compareKeys(left.get(li), leftKeys, left.get(ls), leftKeys, keyCmp) == 0) li++;
				while (ri < right.size() && compareKeys(right.get(ri), rightKeys, right.get(rs), rightKeys, keyCmp) == 0) ri++;
				for (int l = ls; l < li; l++) {
					boolean matched = false;
					for (int r = rs; r < ri && !matched; r++) {
						matched = matchesResidual(combineRows(left.get(l), right.get(r), lw, rw), rowType, residual);
					}
					if (matched != anti) result.add(Arrays.copyOf(left.get(l), lw));
				}
			}
		}
	}

	// ========== Helpers ==========

	/**
	 * Removes from {@code rows} those with NULL in a key whose {@code nullsExcluded}
	 * flag is set, and returns them.
	 */
	private static List<ACell[]> setApartNullKeys(List<ACell[]> rows, int[] keys, boolean[] nullsExcluded) {
		List<ACell[]> apart = new ArrayList<>();
		rows.removeIf(row -> {
			for (int i = 0; i < keys.length; i++) {
				if (nullsExcluded[i] && (keys[i] >= row.length || row[keys[i]] == null)) {
					apart.add(row);
					return true;
				}
			}
			return false;
		});
		return apart;
	}

	private static List<ACell[]> collect(ConvexEnumerable input) {
		List<ACell[]> rows = new ArrayList<>();
		for (ACell[] row : input) rows.add(row);
		return rows;
	}

	private ACell[] combineRows(ACell[] left, ACell[] right, int lw, int rw) {
		ACell[] combined = new ACell[lw + rw];
		System.arraycopy(left, 0, combined, 0, Math.min(left.length, lw));
		System.arraycopy(right, 0, combined, lw, Math.min(right.length, rw));
		return combined;
	}

	private ACell[] nullRow(int width) {
		return new ACell[width];
	}

	/**
	 * Compares the join key columns of two rows.
	 */
	private static int compareKeys(ACell[] row1, int[] keys1, ACell[] row2, int[] keys2,
			Comparator<ACell> keyCmp) {
		for (int i = 0; i < keys1.length; i++) {
			ACell a = keys1[i] < row1.length ? row1[keys1[i]] : null;
			ACell b = keys2[i] < row2.length ? row2[keys2[i]] : null;
			if (a == null && b == null) continue;
			if (a == null) return -1;
			if (b == null) return 1;
			int cmp = keyCmp.compare(a, b);
			if (cmp != 0) return cmp;
		}
		return 0;
	}

	/**
	 * Creates a row comparator that orders by the given key column indices.
	 */
	private static Comparator<ACell[]> rowComparator(int[] keys, Comparator<ACell> keyCmp) {
		return (row1, row2) -> compareKeys(row1, keys, row2, keys, keyCmp);
	}

	/**
	 * Builds a type-specific key comparator from the left key columns' SQL types.
	 */
	private Comparator<ACell> buildKeyComparator(int[] leftKeys) {
		if (leftKeys.length == 0) return (a, b) -> 0;
		// Use the type of the first key column (most common: single-column join)
		List<RelDataTypeField> fields = getLeft().getRowType().getFieldList();
		SqlTypeName sqlType = (leftKeys[0] < fields.size())
			? fields.get(leftKeys[0]).getType().getSqlTypeName()
			: SqlTypeName.ANY;
		return ConvexExpressionEvaluator.comparatorFor(sqlType);
	}

	/**
	 * Evaluates residual (non-equi) conditions on a combined row.
	 */
	private boolean matchesResidual(ACell[] row, RelDataType rowType, List<RexNode> residual) {
		for (RexNode cond : residual) {
			ACell val = ConvexExpressionEvaluator.evaluate(cond, row, rowType);
			if (!(val instanceof CVMBool b && b.booleanValue())) return false;
		}
		return true;
	}
}
