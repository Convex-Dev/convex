package convex.db.calcite.rules;

import java.util.List;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.logical.LogicalTableScan;

import convex.db.calcite.ConvexTable;
import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.rel.ConvexAggregate;
import convex.db.calcite.rel.ConvexFilter;
import convex.db.calcite.rel.ConvexJoin;
import convex.db.calcite.rel.ConvexProject;
import convex.db.calcite.rel.ConvexSort;
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

	/** Rule to convert LogicalSort to ConvexSort. */
	public static final ConvexSortRule SORT = ConvexSortRule.INSTANCE;

	/** Rule to convert LogicalAggregate to ConvexAggregate. */
	public static final ConvexAggregateRule AGGREGATE = ConvexAggregateRule.INSTANCE;

	/** Rule to convert LogicalJoin to ConvexJoin. */
	public static final ConvexJoinRule JOIN = ConvexJoinRule.INSTANCE;

	/** Rule to convert ConvexConvention to EnumerableConvention. */
	public static final ConvexToEnumerableConverterRule TO_ENUMERABLE =
		ConvexToEnumerableConverterRule.INSTANCE;

	/** Returns all Convex query rules for SELECT operations. */
	public static List<RelOptRule> rules() {
		return List.of(TABLE_SCAN, FILTER, PROJECT, SORT, AGGREGATE, JOIN, TO_ENUMERABLE);
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

	// ========== Sort Rule ==========

	public static class ConvexSortRule extends ConverterRule {
		public static final ConvexSortRule INSTANCE = new ConvexSortRule();

		private ConvexSortRule() {
			super(Config.INSTANCE
				.withConversion(LogicalSort.class, Convention.NONE,
					ConvexConvention.INSTANCE, "ConvexSortRule")
				.withRuleFactory(ConvexSortRule::new));
		}

		private ConvexSortRule(Config config) {
			super(config);
		}

		@Override
		public RelNode convert(RelNode rel) {
			LogicalSort sort = (LogicalSort) rel;
			RelTraitSet traitSet = sort.getTraitSet().replace(ConvexConvention.INSTANCE);
			RelNode input = convert(sort.getInput(),
				sort.getInput().getTraitSet().replace(ConvexConvention.INSTANCE));
			return new ConvexSort(sort.getCluster(), traitSet, input,
				sort.getCollation(), sort.offset, sort.fetch);
		}
	}

	// ========== Aggregate Rule ==========

	public static class ConvexAggregateRule extends ConverterRule {
		public static final ConvexAggregateRule INSTANCE = new ConvexAggregateRule();

		private ConvexAggregateRule() {
			super(Config.INSTANCE
				.withConversion(LogicalAggregate.class, Convention.NONE,
					ConvexConvention.INSTANCE, "ConvexAggregateRule")
				.withRuleFactory(ConvexAggregateRule::new));
		}

		private ConvexAggregateRule(Config config) {
			super(config);
		}

		@Override
		public RelNode convert(RelNode rel) {
			LogicalAggregate agg = (LogicalAggregate) rel;
			RelTraitSet traitSet = agg.getTraitSet().replace(ConvexConvention.INSTANCE);
			RelNode input = convert(agg.getInput(),
				agg.getInput().getTraitSet().replace(ConvexConvention.INSTANCE));
			return new ConvexAggregate(agg.getCluster(), traitSet, input,
				agg.getGroupSet(), agg.getGroupSets(), agg.getAggCallList());
		}
	}

	// ========== Join Rule ==========

	public static class ConvexJoinRule extends ConverterRule {
		public static final ConvexJoinRule INSTANCE = new ConvexJoinRule();

		private ConvexJoinRule() {
			super(Config.INSTANCE
				.withConversion(LogicalJoin.class, Convention.NONE,
					ConvexConvention.INSTANCE, "ConvexJoinRule")
				.withRuleFactory(ConvexJoinRule::new));
		}

		private ConvexJoinRule(Config config) {
			super(config);
		}

		@Override
		public RelNode convert(RelNode rel) {
			LogicalJoin join = (LogicalJoin) rel;
			RelTraitSet traitSet = join.getTraitSet().replace(ConvexConvention.INSTANCE);
			RelNode left = convert(join.getLeft(),
				join.getLeft().getTraitSet().replace(ConvexConvention.INSTANCE));
			RelNode right = convert(join.getRight(),
				join.getRight().getTraitSet().replace(ConvexConvention.INSTANCE));
			return new ConvexJoin(join.getCluster(), traitSet, left, right,
				join.getCondition(), join.getVariablesSet(), join.getJoinType());
		}
	}
}
