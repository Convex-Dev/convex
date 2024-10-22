package convex.core.data.type;

import convex.core.cvm.AOp;
import convex.core.cvm.ops.Do;
import convex.core.data.ACell;

/**
 * Type that represents CVM Long values
 */
@SuppressWarnings("rawtypes")
public final class OpCode extends AStandardType<AOp> {
	/**
	 * Singleton runtime instance
	 */
	public static final OpCode INSTANCE = new OpCode();

	private OpCode() {
		super(AOp.class);
	}
	
	@Override
	public boolean check(ACell value) {
		return value instanceof AOp;
	}
	
	@Override
	public String toString () {
		return "Op";
	}

	@Override
	public AOp defaultValue() {
		return Do.EMPTY;
	}

	@Override
	public AOp implicitCast(ACell a) {
		if (a instanceof AOp) return (AOp)a;
		return null;
	}

}
