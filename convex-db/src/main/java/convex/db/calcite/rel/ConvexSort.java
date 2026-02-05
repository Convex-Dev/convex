package convex.db.calcite.rel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexNode;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.convention.ConvexEnumerable;
import convex.db.calcite.convention.ConvexRel;
import convex.db.calcite.eval.ConvexExpressionEvaluator;

/**
 * Sort in CONVEX convention.
 *
 * <p>Sorts ACell[] rows using RT.compare() for CVM-native ordering.
 */
public class ConvexSort extends Sort implements ConvexRel {

	public ConvexSort(RelOptCluster cluster, RelTraitSet traitSet,
			RelNode input, RelCollation collation, RexNode offset, RexNode fetch) {
		super(cluster, traitSet, input, collation, offset, fetch);
		assert getConvention() == ConvexConvention.INSTANCE;
	}

	@Override
	public ConvexSort copy(RelTraitSet traitSet, RelNode input,
			RelCollation collation, RexNode offset, RexNode fetch) {
		return new ConvexSort(getCluster(), traitSet, input, collation, offset, fetch);
	}

	@Override
	public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
		double rowCount = mq.getRowCount(this);
		// Sort cost is O(n log n)
		double sortCost = rowCount * Math.log(rowCount + 1);
		return planner.getCostFactory().makeCost(rowCount, sortCost, 0);
	}

	@Override
	public ConvexEnumerable execute() {
		ConvexRel inputRel = (ConvexRel) getInput();
		ConvexEnumerable input = inputRel.execute();

		// Collect all rows
		List<ACell[]> rows = new ArrayList<>();
		for (ACell[] row : input) {
			rows.add(row);
		}

		// Sort if there's a collation
		if (!collation.getFieldCollations().isEmpty()) {
			rows.sort(createComparator());
		}

		// Apply OFFSET
		int offsetVal = 0;
		if (offset != null) {
			ACell offsetCell = ConvexExpressionEvaluator.literalToCell(
				(org.apache.calcite.rex.RexLiteral) offset);
			if (offsetCell != null) {
				offsetVal = ((Number) RT.jvm(offsetCell)).intValue();
			}
		}

		// Apply FETCH (LIMIT)
		int fetchVal = rows.size();
		if (fetch != null) {
			ACell fetchCell = ConvexExpressionEvaluator.literalToCell(
				(org.apache.calcite.rex.RexLiteral) fetch);
			if (fetchCell != null) {
				fetchVal = ((Number) RT.jvm(fetchCell)).intValue();
			}
		}

		// Apply offset and limit
		int end = Math.min(offsetVal + fetchVal, rows.size());
		if (offsetVal > 0 || end < rows.size()) {
			if (offsetVal >= rows.size()) {
				rows = List.of();
			} else {
				rows = rows.subList(offsetVal, end);
			}
		}

		return ConvexEnumerable.of(rows);
	}

	/**
	 * Creates a comparator based on the collation using RT.compare().
	 */
	private Comparator<ACell[]> createComparator() {
		List<RelFieldCollation> fields = collation.getFieldCollations();

		return (row1, row2) -> {
			for (RelFieldCollation field : fields) {
				int index = field.getFieldIndex();
				ACell a = index < row1.length ? row1[index] : null;
				ACell b = index < row2.length ? row2[index] : null;

				int cmp = compareValues(a, b);

				// Handle direction
				if (field.getDirection().isDescending()) {
					cmp = -cmp;
				}

				// Handle nulls
				if (cmp == 0 && a == null && b == null) {
					continue; // Both null, equal
				}
				if (a == null) {
					// NULLS FIRST or NULLS LAST
					return field.nullDirection == RelFieldCollation.NullDirection.FIRST ? -1 : 1;
				}
				if (b == null) {
					return field.nullDirection == RelFieldCollation.NullDirection.FIRST ? 1 : -1;
				}

				if (cmp != 0) return cmp;
			}
			return 0;
		};
	}

	/**
	 * Compares two ACell values using RT.compare().
	 */
	private int compareValues(ACell a, ACell b) {
		if (a == null && b == null) return 0;
		if (a == null) return -1;
		if (b == null) return 1;

		// Use RT.compare for CVM-native comparison
		Long cmp = RT.compare(a, b, 0L);
		if (cmp == null) {
			// NaN comparison - treat as equal
			return 0;
		}
		return cmp.intValue();
	}
}
