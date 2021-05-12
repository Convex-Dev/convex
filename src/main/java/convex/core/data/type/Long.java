package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Type that represents CVM Long values
 */
public final class Long extends ANumericType {

	/**
	 * Singleton runtime instance
	 */
	public static final Long INSTANCE = new Long();

	private Long() {
		
	}
	
	@Override
	public boolean check(ACell value) {
		return value instanceof CVMLong;
	}
	
	@Override
	public String toString () {
		return "Long";
	}

	@Override
	public boolean allowsNull() {
		return false;
	}

	@Override
	protected CVMLong defaultValue() {
		return CVMLong.ZERO;
	}

	@Override
	protected CVMLong implicitCast(ACell a) {
		return RT.ensureLong(a);
	}

}
