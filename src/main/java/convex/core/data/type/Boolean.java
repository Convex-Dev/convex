package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;

/**
 * Type that represents CVM Long values
 */
public final class Boolean extends AType {

	/**
	 * Singleton runtime instance
	 */
	public static final Boolean INSTANCE = new Boolean();

	private Boolean() {
		
	}
	
	@Override
	public boolean check(ACell value) {
		return value instanceof CVMBool;
	}
	
	@Override
	public String toString () {
		return "Boolean";
	}

	@Override
	public boolean allowsNull() {
		return false;
	}

	@Override
	protected CVMBool defaultValue() {
		return CVMBool.FALSE;
	}

	@Override
	protected ACell implicitCast(ACell a) {
		// TODO Auto-generated method stub
		return RT.bool(a)?CVMBool.TRUE:CVMBool.FALSE;
	}

}
