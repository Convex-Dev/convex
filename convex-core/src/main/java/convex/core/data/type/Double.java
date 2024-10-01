package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.prim.CVMDouble;
import convex.core.lang.RT;

/**
 * Type that represents CVM Double values
 */
public final class Double extends ANumericType<CVMDouble> {

	/**
	 * Singleton runtime instance
	 */
	public static final Double INSTANCE = new Double();

	private Double() {
		super (CVMDouble.class);
	}
	
	@Override
	public boolean check(ACell value) {
		return value instanceof CVMDouble;
	}
	
	@Override
	public String toString () {
		return "Double";
	}

	@Override
	public CVMDouble defaultValue() {
		return CVMDouble.ONE;
	}

	@Override
	public CVMDouble implicitCast(ACell a) {
		return RT.ensureDouble(a);
	}
}
