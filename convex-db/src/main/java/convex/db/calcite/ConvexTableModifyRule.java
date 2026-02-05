package convex.db.calcite;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;

/**
 * Rule that converts a {@link ConvexLogicalTableModify} to a {@link ConvexTableModify}
 * in the ENUMERABLE convention.
 *
 * <p>This rule handles the convention conversion from NONE to ENUMERABLE,
 * ensuring that the input is also properly converted.
 */
public class ConvexTableModifyRule extends ConverterRule {

	public static final ConvexTableModifyRule INSTANCE = Config.INSTANCE
		.withConversion(ConvexLogicalTableModify.class, Convention.NONE,
			EnumerableConvention.INSTANCE, "ConvexTableModifyRule")
		.withRuleFactory(ConvexTableModifyRule::new)
		.toRule(ConvexTableModifyRule.class);

	protected ConvexTableModifyRule(Config config) {
		super(config);
	}

	@Override
	public RelNode convert(RelNode rel) {
		final ConvexLogicalTableModify modify = (ConvexLogicalTableModify) rel;

		// Convert input to ENUMERABLE convention
		final RelTraitSet traitSet = modify.getTraitSet().replace(EnumerableConvention.INSTANCE);
		final RelNode convertedInput = convert(modify.getInput(), traitSet);

		return new ConvexTableModify(
			modify.getCluster(),
			traitSet,
			modify.getTable(),
			modify.getCatalogReader(),
			convertedInput,
			modify.getOperation(),
			modify.getUpdateColumnList(),
			modify.getSourceExpressionList(),
			modify.isFlattened());
	}
}
