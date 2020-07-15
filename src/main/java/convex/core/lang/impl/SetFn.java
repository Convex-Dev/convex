package convex.core.lang.impl;

import convex.core.data.ASet;
import convex.core.lang.Context;
import convex.core.lang.IFn;

public class SetFn<T> implements IFn<Boolean> {

	private ASet<T> set;

	public SetFn(ASet<T> m) {
		this.set = m;
	}

	public static <T> SetFn<T> wrap(ASet<T> m) {
		return new SetFn<T>(m);
	}

	@Override
	public <I> Context<Boolean> invoke(Context<I> context, Object[] args) {
		int n = args.length;
		if (n == 1) {
			Object key = args[0];
			Boolean result = set.contains(key);
			return context.withResult(result);
		} else {
			return context.withArityError("Expected arity 1 for set lookup but got: " + n + " in set: " + set);
		}
	}

}
