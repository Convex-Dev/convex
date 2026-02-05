package convex.db.calcite.rules;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;

import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.convention.ConvexRel;
import convex.db.calcite.rel.ConvexToEnumerableConverter;

/**
 * Rule to convert from ConvexConvention to EnumerableConvention.
 *
 * <p>This rule is triggered when the planner needs to convert the output
 * of a ConvexRel tree to the Enumerable convention for execution.
 */
public class ConvexToEnumerableConverterRule extends ConverterRule {

	public static final ConvexToEnumerableConverterRule INSTANCE =
		new ConvexToEnumerableConverterRule();

	private ConvexToEnumerableConverterRule() {
		super(Config.INSTANCE
			.withConversion(RelNode.class, ConvexConvention.INSTANCE,
				EnumerableConvention.INSTANCE, "ConvexToEnumerableConverterRule")
			.withRuleFactory(ConvexToEnumerableConverterRule::new));
	}

	private ConvexToEnumerableConverterRule(Config config) {
		super(config);
	}

	@Override
	public RelNode convert(RelNode rel) {
		RelTraitSet newTraitSet = rel.getTraitSet().replace(EnumerableConvention.INSTANCE);
		return new ConvexToEnumerableConverter(rel.getCluster(), newTraitSet, rel);
	}
}
