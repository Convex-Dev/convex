package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.prim.CVMLong;

public final class Int64 extends ANumericType {

	/**
	 * Singleton runtime instance
	 */
	public static final Int64 INSTANCE = new Int64();

	private Int64() {
		
	}
	
	@Override
	public boolean check(ACell value) {
		return value instanceof CVMLong;
	}
	
	@Override
	public String toString () {
		return "Long";
	}

}
