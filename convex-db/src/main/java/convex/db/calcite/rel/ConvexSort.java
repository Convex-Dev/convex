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
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.DataContext;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.type.SqlTypeName;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.lang.RT;
import convex.db.calcite.ConvexSchema;
import convex.db.calcite.ConvexTable;
import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.convention.ConvexEnumerable;
import convex.db.calcite.convention.ConvexRel;
import convex.db.calcite.eval.ConvexExpressionEvaluator;
import convex.db.lattice.RowBlock;
import convex.db.lattice.SQLRow;
import convex.db.lattice.SQLTable;

/**
 * Sort in CONVEX convention.
 *
 * <p>Sorts ACell[] rows using type-specific comparators selected from
 * the column's SQL type at plan time.
 *
 * <p>For {@code ORDER BY PK [ASC|DESC] LIMIT N} directly over a table scan,
 * uses index-native traversal via {@code entryAt()} to avoid a full scan and
 * in-memory sort. Only N index nodes are visited instead of all n rows.
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
	public ConvexEnumerable execute(DataContext ctx) {
		// Index-native path: ORDER BY PK [ASC|DESC] LIMIT N with no OFFSET,
		// directly over a table scan (no intervening filter or project).
		if (fetch != null && offset == null && getInput() instanceof ConvexTableScan scan) {
			ConvexEnumerable pushed = tryIndexScan(scan, ctx);
			if (pushed != null) return pushed;
		}

		ConvexRel inputRel = (ConvexRel) getInput();
		ConvexEnumerable input = inputRel.execute(ctx);

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
	 * Tries to serve {@code ORDER BY PK [ASC|DESC] LIMIT N} directly from the index,
	 * walking forward or backward with {@code entryAt()} and stopping as soon as
	 * {@code fetchVal} live rows have been collected.
	 *
	 * <p>Applicable when:
	 * <ul>
	 *   <li>collation is a single field on column 0 (the primary key)</li>
	 *   <li>LIMIT is a literal (no OFFSET)</li>
	 *   <li>input is a bare {@link ConvexTableScan} (no filter, no project)</li>
	 * </ul>
	 *
	 * @return a {@link ConvexEnumerable} with at most {@code fetchVal} rows, or
	 *         {@code null} if the optimisation is not applicable
	 */
	private ConvexEnumerable tryIndexScan(ConvexTableScan scan, DataContext ctx) {
		// Single-field sort on PK (column 0) only
		List<RelFieldCollation> fields = collation.getFieldCollations();
		if (fields.size() != 1 || fields.get(0).getFieldIndex() != 0) return null;

		// LIMIT must be a literal
		if (!(fetch instanceof RexLiteral)) return null;
		ACell fetchCell = ConvexExpressionEvaluator.literalToCell((RexLiteral) fetch);
		if (fetchCell == null) return null;
		int fetchVal = ((Number) RT.jvm(fetchCell)).intValue();
		if (fetchVal <= 0) return ConvexEnumerable.empty();

		// Locate the raw index
		ConvexTable convexTable = scan.getTable().unwrap(ConvexTable.class);
		if (convexTable == null) return null;
		ConvexSchema schema = convexTable.getSchema();
		SQLTable sqlTable = schema.getTables().getLiveTable(convexTable.getTableName());
		if (sqlTable == null) return ConvexEnumerable.empty();
		Index<ABlob, ACell> rawRows = sqlTable.getRows();
		if (rawRows == null) return ConvexEnumerable.empty();

		long total = rawRows.count();
		if (total == 0) return ConvexEnumerable.empty();

		boolean desc = fields.get(0).getDirection().isDescending();
		List<ACell[]> result = new ArrayList<>(Math.min(fetchVal, (int) Math.min(total, Integer.MAX_VALUE)));

		if (desc) {
			// Walk backwards through blocks, then rows within each block
			for (long i = total - 1; i >= 0 && result.size() < fetchVal; i--) {
				ACell entry = rawRows.entryAt(i).getValue();
				if (RowBlock.isBlock(entry)) {
					int blockSize = RowBlock.count(entry);
					for (int j = blockSize - 1; j >= 0 && result.size() < fetchVal; j--) {
						AVector<ACell> row = RowBlock.getRow(entry, j);
						if (SQLRow.isLive(row)) result.add(SQLRow.getValues(row).toCellArray());
					}
				} else if (SQLRow.isLive((AVector<ACell>)entry)) {
					result.add(SQLRow.getValues((AVector<ACell>)entry).toCellArray());
				}
			}
		} else {
			// Walk forwards through blocks, then rows within each block
			for (long i = 0; i < total && result.size() < fetchVal; i++) {
				ACell entry = rawRows.entryAt(i).getValue();
				if (RowBlock.isBlock(entry)) {
					int blockSize = RowBlock.count(entry);
					for (int j = 0; j < blockSize && result.size() < fetchVal; j++) {
						AVector<ACell> row = RowBlock.getRow(entry, j);
						if (SQLRow.isLive(row)) result.add(SQLRow.getValues(row).toCellArray());
					}
				} else if (SQLRow.isLive((AVector<ACell>)entry)) {
					result.add(SQLRow.getValues((AVector<ACell>)entry).toCellArray());
				}
			}
		}

		return ConvexEnumerable.of(result);
	}

	/**
	 * Creates a row comparator by selecting a type-specific cell comparator
	 * for each sort field based on the column's SQL type.
	 */
	private Comparator<ACell[]> createComparator() {
		List<RelFieldCollation> fields = collation.getFieldCollations();
		RelDataType inputRowType = getInput().getRowType();
		List<RelDataTypeField> fieldList = inputRowType.getFieldList();

		// Pre-select a comparator per sort field
		int n = fields.size();
		int[] indices = new int[n];
		Comparator<ACell>[] comparators = new Comparator[n];

		for (int i = 0; i < n; i++) {
			RelFieldCollation field = fields.get(i);
			int index = field.getFieldIndex();
			indices[i] = index;

			SqlTypeName sqlType = (index < fieldList.size())
				? fieldList.get(index).getType().getSqlTypeName()
				: SqlTypeName.ANY;

			Comparator<ACell> cmp = ConvexExpressionEvaluator.comparatorFor(sqlType);

			// Wrap with direction and null handling
			boolean nullsFirst = (field.nullDirection == RelFieldCollation.NullDirection.FIRST);
			boolean desc = field.getDirection().isDescending();
			comparators[i] = ConvexExpressionEvaluator.nullSafeComparator(cmp, nullsFirst, desc);
		}

		return (row1, row2) -> {
			for (int i = 0; i < n; i++) {
				int idx = indices[i];
				ACell a = idx < row1.length ? row1[idx] : null;
				ACell b = idx < row2.length ? row2[idx] : null;
				int cmp = comparators[i].compare(a, b);
				if (cmp != 0) return cmp;
			}
			return 0;
		};
	}

}
