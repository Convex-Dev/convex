package convex.core.data.type;

import convex.core.data.ACell;

/**
 * Type that represents any CVM value
 */
public class Any extends AType {

	public static final Any INSTANCE = new Any();
	
	private Any() {
		
	}

	@Override
	public boolean check(ACell value) {
		return true;
	}

	@Override
	public boolean allowsNull() {
		return true;
	}

}
