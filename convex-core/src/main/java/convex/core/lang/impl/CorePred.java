package convex.core.lang.impl;

import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.data.ACell;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMBool;

/**
 * Abstract base class for core predicate functions
 */
public abstract class CorePred extends CoreFn<CVMBool> {

	protected CorePred(Symbol symbol, int code) {
		super(symbol,code);
	}

	@Override
	public Context invoke(Context context, ACell[] args) {
		if (args.length != 1) return context.withArityError(name() + " requires exactly one argument");
		ACell val = args[0];
		// ensure we return one of the two canonical boolean values
		CVMBool result = test(val) ? CVMBool.TRUE : CVMBool.FALSE;
		return context.withResult(Juice.SIMPLE_FN, result);
	}

	public abstract boolean test(ACell val);
}
