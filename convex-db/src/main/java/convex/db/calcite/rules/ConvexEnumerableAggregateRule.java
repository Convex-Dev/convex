package convex.db.calcite.rules;

import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableRules;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;

import convex.db.calcite.ConvexTypeSystem;

/** Converts widened SUM inputs before Calcite generates its Enumerable accumulator. */
public class ConvexEnumerableAggregateRule extends ConverterRule {
	private static final ConverterRule STANDARD = EnumerableRules.ENUMERABLE_AGGREGATE_RULE;
	public static final ConvexEnumerableAggregateRule INSTANCE = new ConvexEnumerableAggregateRule();

	private ConvexEnumerableAggregateRule() {
		super(Config.INSTANCE.withConversion(LogicalAggregate.class, Convention.NONE,
				EnumerableConvention.INSTANCE, "ConvexEnumerableAggregateRule")
				.withRuleFactory(config -> INSTANCE));
	}

	@Override
	public RelNode convert(RelNode rel) {
		LogicalAggregate agg = (LogicalAggregate) rel;
		if (agg.getCluster().getTypeFactory().getTypeSystem() != ConvexTypeSystem.INSTANCE) {
			return STANDARD.convert(rel);
		}
		var input = agg.getInput();
		var rex = agg.getCluster().getRexBuilder();
		List<RexNode> expressions = new ArrayList<>();
		List<String> names = new ArrayList<>(input.getRowType().getFieldNames());
		for (int i = 0; i < names.size(); i++) expressions.add(rex.makeInputRef(input, i));
		List<AggregateCall> calls = new ArrayList<>();
		for (AggregateCall call : agg.getAggCallList()) {
			SqlKind kind = call.getAggregation().getKind();
			if ((kind == SqlKind.SUM || kind == SqlKind.SUM0)
					&& call.getType().getSqlTypeName() == SqlTypeName.DECIMAL
					&& call.getArgList().size() == 1) {
				RexNode argument = expressions.get(call.getArgList().get(0));
				if (argument.getType().getSqlTypeName() != SqlTypeName.DECIMAL) {
					// Calcite 1.42's SumImplementor calls BigDecimal.add directly, without
					// converting a widened integer argument. AVG is reduced to SUM0/COUNT.
					int index = expressions.size();
					expressions.add(rex.makeCast(call.getType(), argument));
					names.add("$sum" + index);
					call = call.withArgList(List.of(index));
				}
			}
			calls.add(call);
		}
		if (expressions.size() != input.getRowType().getFieldCount()) {
			RelNode project = LogicalProject.create(input, List.of(), expressions, names);
			agg = agg.copy(agg.getTraitSet(), project, agg.getGroupSet(), agg.getGroupSets(), calls);
		}
		return STANDARD.convert(agg);
	}
}
