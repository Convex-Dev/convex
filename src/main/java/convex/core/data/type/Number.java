package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.lang.RT;

public class Number extends ANumericType {
	
	public static final Number INSTANCE = new Number();
	
	private Number() {
		
	}

	@Override
	public boolean check(ACell value) {
		return RT.isNumber(value);
	}

	@Override
	public boolean allowsNull() {
		return false;
	}

	@Override
	public String toString() {
		return "Number";
	}

}
