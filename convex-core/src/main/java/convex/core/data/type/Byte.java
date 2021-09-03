package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.prim.CVMByte;

/**
 * Type that represents CVM Byte values
 */
public final class Byte extends ANumericType<CVMByte> {

	/**
	 * Singleton runtime instance
	 */
	public static final Byte INSTANCE = new Byte();

	private Byte() {
		super (CVMByte.class);
	}
	
	@Override
	public boolean check(ACell value) {
		return value instanceof CVMByte;
	}
	
	@Override
	public String toString () {
		return "Byte";
	}

	@Override
	public CVMByte defaultValue() {
		return CVMByte.ZERO;
	}

	@Override
	public CVMByte implicitCast(ACell a) {
		if (a instanceof CVMByte) return (CVMByte)a;
		return null;
	}
}
