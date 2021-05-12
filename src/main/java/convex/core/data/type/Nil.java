package convex.core.data.type;

import convex.core.data.ACell;

/**
 * The Type representing the single value 'nil'
 */
public class Nil extends AType {

	public static final Nil INSTANCE = new Nil();

	@Override
	public boolean check(ACell value) {
		return value==null;
	}

	@Override
	public boolean allowsNull() {
		return true;
	}

}
