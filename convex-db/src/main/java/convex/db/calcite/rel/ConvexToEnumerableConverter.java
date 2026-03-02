package convex.db.calcite.rel;

import java.util.List;

import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterImpl;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;

import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.convention.ConvexRel;

/**
 * Converter from ConvexConvention to EnumerableConvention.
 *
 * <p>This is the boundary where CVM types (ACell[]) are converted to
 * Java types (Object[]) for consumption by Calcite's enumerable layer.
 *
 * <p>The converter registers the ConvexRel tree and generates code that:
 * <ol>
 *   <li>Looks up the registered ConvexRel by ID</li>
 *   <li>Executes the tree to get ConvexEnumerable&lt;ACell[]&gt;</li>
 *   <li>Maps each ACell[] to Object[] using type conversion</li>
 *   <li>Returns the result as Enumerable&lt;Object[]&gt;</li>
 * </ol>
 */
public class ConvexToEnumerableConverter extends ConverterImpl implements EnumerableRel {

	public ConvexToEnumerableConverter(
			RelOptCluster cluster,
			RelTraitSet traits,
			RelNode input) {
		super(cluster, ConventionTraitDef.INSTANCE, traits, input);
	}

	@Override
	public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
		return new ConvexToEnumerableConverter(
			getCluster(), traitSet, sole(inputs));
	}

	@Override
	public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
		// Small cost for the conversion
		double rowCount = mq.getRowCount(this);
		return planner.getCostFactory().makeCost(rowCount, rowCount, 0);
	}

	@Override
	public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
		// Get the physical type for output
		final BlockBuilder builder = new BlockBuilder();
		final PhysType physType = PhysTypeImpl.of(
			implementor.getTypeFactory(),
			getRowType(),
			pref.preferArray());

		// Get the ConvexRel input
		final RelNode input = getInput();
		if (!(input instanceof ConvexRel convexRel)) {
			throw new IllegalStateException("Expected ConvexRel input but got: " + input.getClass());
		}

		// Register the ConvexRel tree for runtime execution
		final long id = ConvexRelExecutor.register(convexRel);
		final int fieldCount = getRowType().getFieldCount();

		// Generate code: ConvexRelExecutor.execute(id, fieldCount)
		Expression executeExpr = Expressions.call(
			ConvexRelExecutor.class,
			"execute",
			Expressions.constant(id),
			Expressions.constant(fieldCount));

		builder.add(Expressions.return_(null, executeExpr));

		return implementor.result(physType, builder.toBlock());
	}
}
