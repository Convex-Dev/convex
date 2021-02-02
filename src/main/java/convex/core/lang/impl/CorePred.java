package convex.core.lang.impl;

import convex.core.data.Symbol;
import convex.core.data.prim.CVMBool;
import convex.core.lang.Context;
import convex.core.lang.Juice;

/**
 * Abstract base class for core predicate functions
 */
public abstract class CorePred extends CoreFn<CVMBool> {

	protected CorePred(Symbol symbol) {
		super(symbol);
	}

	@SuppressWarnings("unchecked")
	@Override
	public Context<CVMBool> invoke(@SuppressWarnings("rawtypes") Context context, Object[] args) {
		if (args.length != 1) return context.withArityError(name() + " requires exactly one argument");
		Object val = args[0];
		// ensure we return one of the two canonical boolean values
		CVMBool result = test(val) ? CVMBool.TRUE : CVMBool.FALSE;
		return context.withResult(Juice.SIMPLE_FN, result);
	}

	public abstract boolean test(Object val);
}
