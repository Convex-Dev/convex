package convex.db.calcite.rel;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.calcite.DataContext;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.ImmutableBitSet;

import convex.core.data.ACell;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.ANumeric;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.convention.ConvexEnumerable;
import convex.db.calcite.convention.ConvexRel;

/**
 * Aggregate in CONVEX convention.
 *
 * <p>Performs aggregation using CVM types and RT functions:
 * <ul>
 *   <li>SUM - uses RT.plus()</li>
 *   <li>COUNT - simple counter</li>
 *   <li>MIN/MAX - uses RT.min()/RT.max()</li>
 *   <li>AVG - SUM/COUNT with CVMDouble</li>
 *   <li>SINGLE_VALUE - the one value of a scalar subquery, NULL for none, an error for more</li>
 * </ul>
 *
 * <p>An aggregate with a {@code FILTER (WHERE ...)} clause sees only the rows the filter
 * admits. Calcite also produces that clause itself when it lifts a CASE out of an
 * aggregate's argument, so {@code MAX(CASE WHEN c THEN x END)} arrives as
 * {@code MAX(x) FILTER (WHERE c IS TRUE)}.
 */
public class ConvexAggregate extends Aggregate implements ConvexRel {

	public ConvexAggregate(RelOptCluster cluster, RelTraitSet traitSet,
			RelNode input, ImmutableBitSet groupSet,
			List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) {
		super(cluster, traitSet, List.of(), input, groupSet, groupSets, aggCalls);
		assert getConvention() == ConvexConvention.INSTANCE;
	}

	@Override
	public ConvexAggregate copy(RelTraitSet traitSet, RelNode input,
			ImmutableBitSet groupSet, List<ImmutableBitSet> groupSets,
			List<AggregateCall> aggCalls) {
		return new ConvexAggregate(getCluster(), traitSet, input, groupSet, groupSets, aggCalls);
	}

	@Override
	public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
		double rowCount = mq.getRowCount(getInput());
		return planner.getCostFactory().makeCost(rowCount, rowCount * getAggCallList().size(), 0);
	}

	@Override
	public ConvexEnumerable execute(DataContext ctx) {
		ConvexRel inputRel = (ConvexRel) getInput();
		ConvexEnumerable input = inputRel.execute(ctx);
		RelDataType inputRowType = getInput().getRowType();

		List<Integer> groupKeys = groupSet.toList();
		List<AggregateCall> aggCalls = getAggCallList();

		// Group rows by group key
		Map<GroupKey, List<ACell[]>> groups = new HashMap<>();

		for (ACell[] row : input) {
			GroupKey key = new GroupKey(row, groupKeys);
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
		}

		// If no groups and no rows, still produce one row for aggregates without GROUP BY
		if (groups.isEmpty() && groupKeys.isEmpty()) {
			groups.put(new GroupKey(new ACell[0], groupKeys), new ArrayList<>());
		}

		// Compute aggregates for each group
		List<ACell[]> result = new ArrayList<>();
		for (Map.Entry<GroupKey, List<ACell[]>> entry : groups.entrySet()) {
			GroupKey key = entry.getKey();
			List<ACell[]> groupRows = entry.getValue();

			// Build result row: group keys + aggregate values
			ACell[] resultRow = new ACell[groupKeys.size() + aggCalls.size()];

			// Copy group key values
			for (int i = 0; i < groupKeys.size(); i++) {
				resultRow[i] = key.values[i];
			}

			// Compute each aggregate
			for (int i = 0; i < aggCalls.size(); i++) {
				AggregateCall aggCall = aggCalls.get(i);
				resultRow[groupKeys.size() + i] = computeAggregate(aggCall, groupRows, inputRowType);
			}

			result.add(resultRow);
		}

		return ConvexEnumerable.of(result);
	}

	/**
	 * Computes an aggregate value using CVM operations.
	 */
	private ACell computeAggregate(AggregateCall aggCall, List<ACell[]> rows, RelDataType inputRowType) {
		SqlKind kind = aggCall.getAggregation().getKind();
		List<Integer> argList = aggCall.getArgList();

		// FILTER (WHERE ...): only the rows whose filter column is true take part
		if (aggCall.hasFilter()) {
			int filterIndex = aggCall.filterArg;
			List<ACell[]> admitted = new ArrayList<>();
			for (ACell[] row : rows) {
				if (filterIndex < row.length && RT.bool(row[filterIndex])) admitted.add(row);
			}
			rows = admitted;
		}

		// Get column index (-1 for COUNT(*))
		int colIndex = argList.isEmpty() ? -1 : argList.get(0);

		return switch (kind) {
			case COUNT -> computeCount(rows, colIndex, aggCall.isDistinct());
			case SUM, SUM0 -> computeSum(rows, colIndex);
			case MIN -> computeMin(rows, colIndex);
			case MAX -> computeMax(rows, colIndex);
			case AVG -> computeAvg(rows, colIndex, aggCall.getType());
			case SINGLE_VALUE -> computeSingleValue(rows, colIndex);
			default -> throw new UnsupportedOperationException("Aggregate not supported: " + kind);
		};
	}

	/**
	 * SINGLE_VALUE, which Calcite wraps around a scalar subquery such as
	 * {@code WHERE x = (SELECT y FROM t WHERE ...)}: no row is NULL, one row is its
	 * value, and more than one is a cardinality violation, SQLSTATE 21000.
	 */
	private ACell computeSingleValue(List<ACell[]> rows, int colIndex) {
		if (rows.isEmpty()) return null;
		if (rows.size() > 1) {
			String message = "Scalar subquery returned " + rows.size() + " rows, where at most one is allowed";
			throw new IllegalStateException(message, new SQLException(message, "21000"));
		}
		ACell[] row = rows.get(0);
		return colIndex < row.length ? row[colIndex] : null;
	}

	/**
	 * COUNT using simple counter.
	 */
	private ACell computeCount(List<ACell[]> rows, int colIndex, boolean distinct) {
		if (colIndex < 0) {
			// COUNT(*)
			return CVMLong.create(rows.size());
		}

		// COUNT(column) - count non-null values
		if (distinct) {
			// COUNT(DISTINCT column)
			return CVMLong.create(rows.stream()
				.map(row -> colIndex < row.length ? row[colIndex] : null)
				.filter(v -> v != null)
				.distinct()
				.count());
		}

		long count = 0;
		for (ACell[] row : rows) {
			if (colIndex < row.length && row[colIndex] != null) {
				count++;
			}
		}
		return CVMLong.create(count);
	}

	/**
	 * SUM using RT.plus().
	 */
	private ACell computeSum(List<ACell[]> rows, int colIndex) {
		if (rows.isEmpty()) return null;

		ANumeric sum = null;
		for (ACell[] row : rows) {
			ACell val = colIndex < row.length ? row[colIndex] : null;
			if (val == null) continue;

			if (sum == null) {
				sum = RT.ensureNumber(val);
			} else {
				sum = RT.plus(new ACell[]{sum, val});
			}
		}
		return sum;
	}

	/**
	 * MIN using RT.min().
	 */
	private ACell computeMin(List<ACell[]> rows, int colIndex) {
		if (rows.isEmpty()) return null;

		ACell min = null;
		for (ACell[] row : rows) {
			ACell val = colIndex < row.length ? row[colIndex] : null;
			if (val == null) continue;

			if (min == null) {
				min = val;
			} else {
				min = RT.min(min, val);
			}
		}
		return min;
	}

	/**
	 * MAX using RT.max().
	 */
	private ACell computeMax(List<ACell[]> rows, int colIndex) {
		if (rows.isEmpty()) return null;

		ACell max = null;
		for (ACell[] row : rows) {
			ACell val = colIndex < row.length ? row[colIndex] : null;
			if (val == null) continue;

			if (max == null) {
				max = val;
			} else {
				max = RT.max(max, val);
			}
		}
		return max;
	}

	/**
	 * AVG — {@code DOUBLE PRECISION} over the approximate types, and {@code NUMERIC}
	 * over the exact types (see {@link convex.db.calcite.ConvexTypeSystem}). The sum is
	 * exact, so an average of integers that divides exactly is an exact integer; any
	 * other average is a double.
	 */
	private ACell computeAvg(List<ACell[]> rows, int colIndex, RelDataType returnType) {
		ANumeric sum = null;
		long count = 0;
		for (ACell[] row : rows) {
			ACell val = colIndex < row.length ? row[colIndex] : null;
			if (val == null) continue;

			ANumeric num = RT.ensureNumber(val);
			if (num != null) {
				sum = (sum == null) ? num : RT.plus(new ACell[]{sum, num});
				count++;
			}
		}

		if (count == 0) return null;
		if (returnType.getSqlTypeName() == SqlTypeName.DECIMAL && sum instanceof AInteger s) {
			AInteger n = CVMLong.create(count);
			if (s.rem(n).isZero()) return s.quot(n);
		}
		return CVMDouble.create(sum.doubleValue() / count);
	}

	/**
	 * Helper class for group keys.
	 */
	private static class GroupKey {
		final ACell[] values;

		GroupKey(ACell[] row, List<Integer> keyIndices) {
			values = new ACell[keyIndices.size()];
			for (int i = 0; i < keyIndices.size(); i++) {
				int idx = keyIndices.get(i);
				values[i] = idx < row.length ? row[idx] : null;
			}
		}

		@Override
		public int hashCode() {
			int h = 1;
			for (ACell v : values) {
				h = 31 * h + (v == null ? 0 : v.hashCode());
			}
			return h;
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof GroupKey other)) return false;
			if (values.length != other.values.length) return false;
			for (int i = 0; i < values.length; i++) {
				ACell a = values[i];
				ACell b = other.values[i];
				if (a == null && b == null) continue;
				if (a == null || b == null) return false;
				if (!a.equals(b)) return false;
			}
			return true;
		}
	}
}
