package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;

/**
 * Type that represents CVM Long values
 */
public final class Boolean extends AStandardType<CVMBool> {
	/**
	 * Singleton runtime instance
	 */
	public static final Boolean INSTANCE = new Boolean();

	private Boolean() {
		super(CVMBool.class);
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
	public CVMBool defaultValue() {
		return CVMBool.FALSE;
	}

	@Override
	public CVMBool implicitCast(ACell a) {
		return RT.bool(a)?CVMBool.TRUE:CVMBool.FALSE;
	}

}
