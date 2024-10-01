package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Type that represents CVM Integer values of arbitrary length
 */
public final class Integer extends ANumericType<AInteger> {

	/**
	 * Singleton runtime instance
	 */
	public static final Integer INSTANCE = new Integer();

	private Integer() {
		super (AInteger.class);
	}
	
	@Override
	public boolean check(ACell value) {
		return value instanceof AInteger;
	}
	
	@Override
	public String toString () {
		return "Integer";
	}

	@Override
	public AInteger defaultValue() {
		return CVMLong.ONE;
	}

	@Override
	public AInteger implicitCast(ACell a) {
		return RT.ensureInteger(a);
	}
}
