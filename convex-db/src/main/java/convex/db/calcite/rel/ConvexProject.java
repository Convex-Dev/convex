package convex.db.calcite.rel;

import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;

import convex.core.data.ACell;
import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.convention.ConvexEnumerable;
import convex.db.calcite.convention.ConvexRel;
import convex.db.calcite.eval.ConvexExpressionEvaluator;

/**
 * Project in CONVEX convention.
 *
 * <p>Projects columns from ACell[] rows, evaluating expressions as needed.
 */
public class ConvexProject extends Project implements ConvexRel {

	public ConvexProject(RelOptCluster cluster, RelTraitSet traitSet,
			RelNode input, List<? extends RexNode> projects, RelDataType rowType) {
		super(cluster, traitSet, List.of(), input, projects, rowType);
		assert getConvention() == ConvexConvention.INSTANCE;
	}

	@Override
	public ConvexProject copy(RelTraitSet traitSet, RelNode input,
			List<RexNode> projects, RelDataType rowType) {
		return new ConvexProject(getCluster(), traitSet, input, projects, rowType);
	}

	@Override
	public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
		double rowCount = mq.getRowCount(this);
		return planner.getCostFactory().makeCost(rowCount, rowCount * getProjects().size(), 0);
	}

	@Override
	public ConvexEnumerable execute() {
		ConvexRel inputRel = (ConvexRel) getInput();
		ConvexEnumerable input = inputRel.execute();
		List<RexNode> projects = getProjects();
		RelDataType inputRowType = getInput().getRowType();

		List<ACell[]> result = new ArrayList<>();
		for (ACell[] row : input) {
			ACell[] projected = new ACell[projects.size()];
			for (int i = 0; i < projects.size(); i++) {
				projected[i] = ConvexExpressionEvaluator.evaluate(projects.get(i), row, inputRowType);
			}
			result.add(projected);
		}

		return ConvexEnumerable.of(result);
	}
}
