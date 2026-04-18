package convex.db.calcite.rel;

import java.util.List;

import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.adapter.enumerable.JavaRowFormat;
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

import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.convention.ConvexRel;

/**
 * Converter from ConvexConvention to EnumerableConvention.
 *
 * <p>Registers the ConvexRel tree once at compile time. At runtime,
 * generated code calls the appropriate ConvexResultConverter method:
 * <ul>
 *   <li>SCALAR format (1 column): {@code executeScalar(id, ctx)} → {@code Enumerable<Object>}</li>
 *   <li>ARRAY format (N columns): {@code execute(id, N, ctx)} → {@code Enumerable<Object[]>}</li>
 * </ul>
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
		double rowCount = mq.getRowCount(this);
		return planner.getCostFactory().makeCost(rowCount, rowCount, 0);
	}

	@Override
	public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
		final BlockBuilder builder = new BlockBuilder();
		final int fieldCount = getRowType().getFieldCount();

		final RelNode input = getInput();
		if (!(input instanceof ConvexRel convexRel)) {
			throw new IllegalStateException("Expected ConvexRel input but got: " + input.getClass());
		}

		// Stash the ConvexRel tree in DataContext's internal parameters.
		// Calcite resolves this at runtime — no static registry needed.
		final Expression relExpr = implementor.stash(convexRel, ConvexRel.class);

		final JavaRowFormat format = (fieldCount == 1)
			? JavaRowFormat.SCALAR : JavaRowFormat.ARRAY;

		final PhysType physType = PhysTypeImpl.of(
			implementor.getTypeFactory(), getRowType(), format);

		Expression executeExpr;
		if (format == JavaRowFormat.SCALAR) {
			executeExpr = Expressions.call(
				ConvexResultConverter.class, "executeScalar",
				relExpr,
				implementor.getRootExpression());
		} else {
			executeExpr = Expressions.call(
				ConvexResultConverter.class, "execute",
				relExpr,
				Expressions.constant(fieldCount),
				implementor.getRootExpression());
		}

		builder.add(Expressions.return_(null, executeExpr));
		return implementor.result(physType, builder.toBlock());
	}
}
