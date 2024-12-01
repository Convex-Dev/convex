package convex.core.data.type;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.Keyword;

/**
 * Type that represents CVM Byte values
 */
public final class KeywordType extends AStandardType<Keyword> {

	/**
	 * Singleton runtime instance
	 */
	public static final KeywordType INSTANCE = new KeywordType();

	private KeywordType() {
		super(Keyword.class);
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
	public Keyword defaultValue() {
		return Keywords.FOO;
	}

	@Override
	public Keyword implicitCast(ACell a) {
		if (a instanceof Keyword) return (Keyword)a;
		return null;
	}
}
