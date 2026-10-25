package convex.core.data.type;

import convex.core.cvm.AFn;
import convex.core.data.ACell;
import convex.core.lang.Core;
import convex.core.lang.RT;

/**
 * Type that represents any CVM collection
 */
@SuppressWarnings("rawtypes")
public class Function extends AStandardType<AFn> {

	public static final Function INSTANCE = new Function();
	
	private Function() {
		super(AFn.class);
	}

	@Override
	public boolean check(ACell value) {
		return RT.ensureFunction(value)!=null;
	}

	@Override
	public String toString() {
		return "Function";
	}

	@Override
	public AFn<?> defaultValue() {
		return Core.VECTOR;
	}

	@Override
	public AFn implicitCast(ACell a) {
		return RT.ensureFunction(a);
	}

}
