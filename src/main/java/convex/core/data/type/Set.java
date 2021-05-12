package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Sets;

/**
 * Type that represents any CVM collection
 */
public class Set extends AType {

	public static final Set INSTANCE = new Set();
	
	private Set() {
		
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof ASet);
	}

	@Override
	public boolean allowsNull() {
		return false;
	}

	@Override
	public String toString() {
		return "Set";
	}

	@Override
	protected ASet<?> defaultValue() {
		return Sets.empty();
	}

}
