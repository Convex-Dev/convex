package convex.db.calcite;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.logical.LogicalTableModify;
import org.apache.calcite.schema.ModifiableTable;

/**
 * Rule that converts a {@link LogicalTableModify} to a {@link ConvexTableModify}
 * when the target table is a {@link ConvexTable}.
 *
 * <p>This rule handles cases where Calcite creates a LogicalTableModify directly
 * (e.g., for UPDATE and DELETE operations) instead of calling toModificationRel().
 */
public class ConvexLogicalTableModifyRule extends ConverterRule {

	public static final ConvexLogicalTableModifyRule INSTANCE = Config.INSTANCE
		.withConversion(LogicalTableModify.class, Convention.NONE,
			EnumerableConvention.INSTANCE, "ConvexLogicalTableModifyRule")
		.withRuleFactory(ConvexLogicalTableModifyRule::new)
		.toRule(ConvexLogicalTableModifyRule.class);

	protected ConvexLogicalTableModifyRule(Config config) {
		super(config);
	}

	@Override
	public boolean matches(RelOptRuleCall call) {
		final LogicalTableModify modify = call.rel(0);
		// Only match if the table is a ConvexTable
		final ModifiableTable table = modify.getTable().unwrap(ModifiableTable.class);
		return table instanceof ConvexTable;
	}

	@Override
	public RelNode convert(RelNode rel) {
		final LogicalTableModify modify = (LogicalTableModify) rel;

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
