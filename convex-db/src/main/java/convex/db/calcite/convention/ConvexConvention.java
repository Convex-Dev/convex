package convex.db.calcite.convention;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.plan.RelTraitDef;

/**
 * Calling convention that uses ACell[] for row representation.
 *
 * <p>This convention keeps CVM types throughout the execution pipeline,
 * only converting to Java types at the JDBC ResultSet boundary.
 *
 * <p>Rules for this convention are registered via {@link convex.db.jdbc.ConvexDriver}
 * when the driver is loaded.
 */
public enum ConvexConvention implements Convention {
	INSTANCE;

	@Override
	public Class<?> getInterface() {
		return ConvexRel.class;
	}

	@Override
	public String getName() {
		return "CONVEX";
	}

	@Override
	public RelTraitDef<Convention> getTraitDef() {
		return ConventionTraitDef.INSTANCE;
	}

	@Override
	public boolean satisfies(RelTrait trait) {
		return this == trait;
	}

	@Override
	public void register(RelOptPlanner planner) {
		// Rules are registered via ConvexDriver's PLANNER hook
	}

	@Override
	public boolean canConvertConvention(Convention toConvention) {
		return false; // No automatic conversion for now
	}

	@Override
	public boolean useAbstractConvertersForConversion(
			org.apache.calcite.plan.RelTraitSet fromTraits,
			org.apache.calcite.plan.RelTraitSet toTraits) {
		return false;
	}

	@Override
	public String toString() {
		return getName();
	}
}
