package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.prim.CVMBigDecimal;
import convex.core.data.prim.CVMLong;

/**
 * Type that represents CVM Decimal values (arbitrary precision)
 */
public final class Decimal extends ANumericType<CVMBigDecimal> {

	/**
	 * Singleton runtime instance
	 */
	public static final Decimal INSTANCE = new Decimal();

	private Decimal() {
		super(CVMBigDecimal.class);
	}

	@Override
	public boolean check(ACell value) {
		return value instanceof CVMBigDecimal;
	}

	@Override
	public String toString() {
		return "Decimal";
	}

	@Override
	public CVMBigDecimal defaultValue() {
		return CVMBigDecimal.fromLong(0);
	}

	@Override
	public CVMBigDecimal implicitCast(ACell a) {
		if (a instanceof CVMBigDecimal bd) return bd;
		if (a instanceof CVMLong l) return CVMBigDecimal.fromLong(l.longValue());
		return null;
	}
}
