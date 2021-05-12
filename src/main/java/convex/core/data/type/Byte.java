package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.prim.CVMByte;

/**
 * Type that represents CVM Byte values
 */
public final class Byte extends ANumericType {

	/**
	 * Singleton runtime instance
	 */
	public static final Byte INSTANCE = new Byte();

	private Byte() {
		
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
	public boolean allowsNull() {
		return false;
	}

	@Override
	protected CVMByte defaultValue() {
		return CVMByte.ZERO;
	}

}
