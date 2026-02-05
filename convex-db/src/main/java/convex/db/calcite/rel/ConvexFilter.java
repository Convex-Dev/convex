package convex.db.calcite.rel;

import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexNode;

import convex.core.data.ACell;
import convex.core.data.prim.CVMBool;
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
}
