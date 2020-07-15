package convex.core.lang.impl;

import convex.core.data.Symbol;
import convex.core.lang.Context;
import convex.core.lang.Juice;

/**
 * Abstract base class for core predicate functions
 */
public abstract class CorePred extends CoreFn<Boolean> {

	protected CorePred(Symbol symbol) {
		super(symbol);
	}

	@Override
	public <I> Context<Boolean> invoke(Context<I> context, Object[] args) {
		if (args.length != 1) return context.withArityError(name() + " requires exactly one argument");
		Object val = args[0];
		// ensure we return one of the two canonical boolean values
		Boolean result = test(val) ? Boolean.TRUE : Boolean.FALSE;
		return context.withResult(Juice.SIMPLE_FN, result);
	}

	public abstract boolean test(Object val);
}
