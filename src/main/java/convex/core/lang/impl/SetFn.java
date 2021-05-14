package convex.core.lang.impl;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.prim.CVMBool;
import convex.core.lang.Context;

public class SetFn<T extends ACell> extends ADataFn<CVMBool> {

	private ASet<T> set;

	public SetFn(ASet<T> m) {
		this.set = m;
	}

	public static <T extends ACell> SetFn<T> wrap(ASet<T> m) {
		return new SetFn<T>(m);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public Context<CVMBool> invoke(Context context, ACell[] args) {
		int n = args.length;
		if (n == 1) {
			ACell key = args[0];
			CVMBool result = CVMBool.create(set.contains(key));
			return context.withResult(result);
		} else {
			return context.withArityError("Expected arity 1 for set lookup but got: " + n + " in set: " + set);
		}
	}

	@Override
	public void ednString(StringBuilder sb) {
		set.ednString(sb);
	}

	@Override
	public void print(StringBuilder sb) {
		set.print(sb);
	}

}
