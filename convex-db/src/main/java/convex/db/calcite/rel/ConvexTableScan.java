package convex.db.calcite.rel;

import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.DataContext;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.RelMetadataQuery;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.db.calcite.ConvexSchema;
import convex.db.calcite.ConvexTable;
import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.convention.ConvexEnumerable;
import convex.db.calcite.convention.ConvexRel;
import convex.db.lattice.SQLRow;
import convex.db.lattice.SQLSchema;
import convex.db.lattice.SQLTable;

/**
 * Table scan in CONVEX convention.
 *
 * <p>Reads rows from SQLSchema and returns them as ACell[] directly,
 * with no conversion to Java types.
 */
public class ConvexTableScan extends TableScan implements ConvexRel {

	public ConvexTableScan(RelOptCluster cluster, RelTraitSet traitSet, RelOptTable table) {
		super(cluster, traitSet, List.of(), table);
		assert getConvention() == ConvexConvention.INSTANCE;
	}

	@Override
	public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
		// Simple cost model based on row count
		double rowCount = mq.getRowCount(this);
		return planner.getCostFactory().makeCost(rowCount, rowCount, 0);
	}

	@Override
	public ConvexEnumerable execute(DataContext ctx) {
		ConvexTable convexTable = table.unwrap(ConvexTable.class);
		if (convexTable == null) {
			return ConvexEnumerable.empty();
		}

		ConvexSchema schema = convexTable.getSchema();
		SQLSchema tables = schema.getTables();
		String tableName = convexTable.getTableName();

		SQLTable sqlTable = tables.getLiveTable(tableName);
		if (sqlTable == null) return ConvexEnumerable.empty();

		Index<ABlob, AVector<ACell>> rawRows = sqlTable.getRows();
		if (rawRows == null) return ConvexEnumerable.empty();

		// Single-pass tree traversal via forEach, skip tombstones, unwrap values
		List<ACell[]> result = new ArrayList<>((int) Math.min(rawRows.count(), Integer.MAX_VALUE));
		rawRows.forEach((k, v) -> {
			if (SQLRow.isLive(v)) {
				result.add(SQLRow.getValues(v).toCellArray());
			}
		});

		return ConvexEnumerable.of(result);
	}
}
