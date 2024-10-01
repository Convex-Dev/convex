package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.StringShort;

/**
 * Type that represents CVM Byte values
 */
public final class StringType extends AStandardType<AString> {
	
	private static final StringShort DEFAULT_VALUE=StringShort.create("A");
	/**
	 * Singleton runtime instance
	 */
	public static final StringType INSTANCE = new StringType();

	private StringType() {
		super (AString.class);
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
	public AString defaultValue() {
		return DEFAULT_VALUE;
	}

	@Override
	public AString implicitCast(ACell a) {
		if (a instanceof AString) return (AString)a;
		return null;
	}
}
