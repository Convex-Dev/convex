package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.Keyword;
import convex.core.data.Keywords;

/**
 * Type that represents CVM Byte values
 */
public final class KeywordType extends AType {

	/**
	 * Singleton runtime instance
	 */
	public static final KeywordType INSTANCE = new KeywordType();

	private KeywordType() {
		
	}
	
	@Override
	public boolean check(ACell value) {
		return value instanceof Keyword;
	}
	
	@Override
	public String toString () {
		return "Keyword";
	}

	@Override
	public boolean allowsNull() {
		return false;
	}

	@Override
	protected Keyword defaultValue() {
		return Keywords.FOO;
	}

	@Override
	protected Keyword implicitCast(ACell a) {
		if (a instanceof Keyword) return (Keyword)a;
		return null;
	}

	@Override
	protected Class<? extends ACell> getJavaClass() {
		return Keyword.class;
	}
}
