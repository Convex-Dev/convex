package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.prim.CVMChar;

/**
 * Type that represents CVM Byte values
 */
public final class CharacterType extends AStandardType<CVMChar> {

	/**
	 * Singleton runtime instance
	 */
	public static final CharacterType INSTANCE = new CharacterType();

	private CharacterType() {
		super(CVMChar.class);
	}
	
	@Override
	public boolean check(ACell value) {
		return value instanceof CVMChar;
	}
	
	@Override
	public String toString () {
		return "Character";
	}

	@Override
	public CVMChar defaultValue() {
		return CVMChar.ZERO;
	}

	@Override
	public CVMChar implicitCast(ACell a) {
		if (a instanceof CVMChar) return (CVMChar)a;
		return null;
	}
}
