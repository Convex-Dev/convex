package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Sets;

/**
 * Type that represents any CVM collection
 */
@SuppressWarnings("rawtypes")
public class Set extends AStandardType<ASet> {

	public static final Set INSTANCE = new Set();
	
	private Set() {
		super(ASet.class);
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof ASet);
	}

	@Override
	public String toString() {
		return "Set";
	}

	@Override
	public ASet<?> defaultValue() {
		return Sets.empty();
	}

	@Override
	public ASet<?> implicitCast(ACell a) {
		if (a instanceof ASet) return (ASet<?>)a;
		return null;
	}
}
