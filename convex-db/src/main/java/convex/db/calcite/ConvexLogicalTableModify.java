package convex.db.calcite;

import java.util.List;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rex.RexNode;

/**
 * Logical representation of a modification to a ConvexTable.
 *
 * <p>This is used in the NONE convention during planning. The
 * {@link ConvexTableModifyRule} converts it to a {@link ConvexTableModify}
 * in the ENUMERABLE convention.
 *
 * <p>This separate class ensures that Calcite's built-in EnumerableTableModifyRule
 * doesn't try to convert this node (which would fail for UPDATE/DELETE).
 */
public class ConvexLogicalTableModify extends TableModify {

	public ConvexLogicalTableModify(
			RelOptCluster cluster,
			RelTraitSet traitSet,
			RelOptTable table,
			Prepare.CatalogReader catalogReader,
			RelNode input,
			Operation operation,
			List<String> updateColumnList,
			List<RexNode> sourceExpressionList,
			boolean flattened) {
		super(cluster, traitSet, table, catalogReader, input, operation,
			updateColumnList, sourceExpressionList, flattened);
	}

	/**
	 * Creates a ConvexLogicalTableModify with NONE convention.
	 */
	public static ConvexLogicalTableModify create(
			RelOptTable table,
			Prepare.CatalogReader catalogReader,
			RelNode input,
			Operation operation,
			List<String> updateColumnList,
			List<RexNode> sourceExpressionList,
			boolean flattened) {
		final RelOptCluster cluster = input.getCluster();
		final RelTraitSet traitSet = cluster.traitSetOf(Convention.NONE);
		return new ConvexLogicalTableModify(
			cluster, traitSet, table, catalogReader, input, operation,
			updateColumnList, sourceExpressionList, flattened);
	}

	@Override
	public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
		return new ConvexLogicalTableModify(
			getCluster(), traitSet, getTable(), getCatalogReader(),
			sole(inputs), getOperation(), getUpdateColumnList(),
			getSourceExpressionList(), isFlattened());
	}
}
