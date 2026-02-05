package convex.db.calcite.rules;

import java.util.List;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalTableScan;

import convex.db.calcite.ConvexTable;
import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.rel.ConvexFilter;
import convex.db.calcite.rel.ConvexProject;
import convex.db.calcite.rel.ConvexTableScan;

/**
 * Rules to convert logical operators to CONVEX convention.
 */
public class ConvexRules {

	private ConvexRules() {}

	/** Rule to convert LogicalTableScan to ConvexTableScan. */
	public static final ConvexTableScanRule TABLE_SCAN = ConvexTableScanRule.INSTANCE;

	/** Rule to convert LogicalFilter to ConvexFilter. */
	public static final ConvexFilterRule FILTER = ConvexFilterRule.INSTANCE;

	/** Rule to convert LogicalProject to ConvexProject. */
	public static final ConvexProjectRule PROJECT = ConvexProjectRule.INSTANCE;

	/** Rule to convert ConvexConvention to EnumerableConvention. */
	public static final ConvexToEnumerableConverterRule TO_ENUMERABLE =
		ConvexToEnumerableConverterRule.INSTANCE;

	/** Returns all Convex query rules for SELECT operations. */
	public static List<RelOptRule> rules() {
		return List.of(TABLE_SCAN, FILTER, PROJECT, TO_ENUMERABLE);
	}

	/** Alias for rules() - returns query rules. */
	public static List<RelOptRule> queryRules() {
		return rules();
	}

	// ========== Table Scan Rule ==========

	public static class ConvexTableScanRule extends ConverterRule {
		public static final ConvexTableScanRule INSTANCE = new ConvexTableScanRule();

		private ConvexTableScanRule() {
			super(Config.INSTANCE
				.withConversion(LogicalTableScan.class, Convention.NONE,
					ConvexConvention.INSTANCE, "ConvexTableScanRule")
				.withRuleFactory(ConvexTableScanRule::new));
		}

		private ConvexTableScanRule(Config config) {
			super(config);
		}

		@Override
		public boolean matches(RelOptRuleCall call) {
			LogicalTableScan scan = call.rel(0);
			// Only convert if it's a ConvexTable
			return scan.getTable().unwrap(ConvexTable.class) != null;
		}

		@Override
		public RelNode convert(RelNode rel) {
			LogicalTableScan scan = (LogicalTableScan) rel;
			RelTraitSet traitSet = scan.getTraitSet().replace(ConvexConvention.INSTANCE);
			return new ConvexTableScan(scan.getCluster(), traitSet, scan.getTable());
		}
	}

	// ========== Filter Rule ==========

	public static class ConvexFilterRule extends ConverterRule {
		public static final ConvexFilterRule INSTANCE = new ConvexFilterRule();

		private ConvexFilterRule() {
			super(Config.INSTANCE
				.withConversion(LogicalFilter.class, Convention.NONE,
					ConvexConvention.INSTANCE, "ConvexFilterRule")
				.withRuleFactory(ConvexFilterRule::new));
		}

		private ConvexFilterRule(Config config) {
			super(config);
		}

		@Override
		public RelNode convert(RelNode rel) {
			LogicalFilter filter = (LogicalFilter) rel;
			RelTraitSet traitSet = filter.getTraitSet().replace(ConvexConvention.INSTANCE);
			RelNode input = convert(filter.getInput(),
				filter.getInput().getTraitSet().replace(ConvexConvention.INSTANCE));
			return new ConvexFilter(filter.getCluster(), traitSet, input, filter.getCondition());
		}
	}

	// ========== Project Rule ==========

	public static class ConvexProjectRule extends ConverterRule {
		public static final ConvexProjectRule INSTANCE = new ConvexProjectRule();

		private ConvexProjectRule() {
			super(Config.INSTANCE
				.withConversion(LogicalProject.class, Convention.NONE,
					ConvexConvention.INSTANCE, "ConvexProjectRule")
				.withRuleFactory(ConvexProjectRule::new));
		}

		private ConvexProjectRule(Config config) {
			super(config);
		}

		@Override
		public RelNode convert(RelNode rel) {
			LogicalProject project = (LogicalProject) rel;
			RelTraitSet traitSet = project.getTraitSet().replace(ConvexConvention.INSTANCE);
			RelNode input = convert(project.getInput(),
				project.getInput().getTraitSet().replace(ConvexConvention.INSTANCE));
			return new ConvexProject(project.getCluster(), traitSet, input,
				project.getProjects(), project.getRowType());
		}
	}
}
