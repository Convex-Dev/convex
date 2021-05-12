package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Type that represents CVM Byte values
 */
public final class StringType extends AType {

	/**
	 * Singleton runtime instance
	 */
	public static final StringType INSTANCE = new StringType();

	private StringType() {
		
	}
	
	@Override
	public boolean check(ACell value) {
		return value instanceof AString;
	}
	
	@Override
	public String toString () {
		return "String";
	}

	@Override
	public boolean allowsNull() {
		return false;
	}

	@Override
	protected AString defaultValue() {
		return Strings.EMPTY;
	}

	@Override
	protected AString implicitCast(ACell a) {
		if (a instanceof AString) return (AString)a;
		return null;
	}

}
