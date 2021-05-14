package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Type that represents CVM Long values
 */
public final class Long extends ANumericType<CVMLong> {

	/**
	 * Singleton runtime instance
	 */
	public static final Long INSTANCE = new Long();

	private Long() {
		super (CVMLong.class);
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
	public CVMLong defaultValue() {
		return CVMLong.ZERO;
	}

	@Override
	public CVMLong implicitCast(ACell a) {
		return RT.ensureLong(a);
	}
}
