package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.lang.AFn;
import convex.core.lang.Core;

/**
 * Type that represents any CVM collection
 */
public class Function extends AType {

	public static final Function INSTANCE = new Function();
	
	private Function() {
		
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof AFn);
	}

	@Override
	public boolean allowsNull() {
		return false;
	}

	@Override
	public String toString() {
		return "Function";
	}

	@Override
	protected AFn<?> defaultValue() {
		return Core.VECTOR;
	}

	@SuppressWarnings("rawtypes")
	@Override
	protected AFn implicitCast(ACell a) {
		if (a instanceof AFn) return (AFn)a;
		return null;
	}
	
	@Override
	protected Class<? extends ACell> getJavaClass() {
		return AFn.class;
	}

}
