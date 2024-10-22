package convex.core.lang.impl;

import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.prim.CVMBool;
import convex.core.data.util.BlobBuilder;

public class SetFn<T extends ACell> extends ADataFn<CVMBool> {

	private ASet<T> set;

	SetFn(ASet<T> m) {
		this.set = m;
	}

	public static <T extends ACell> SetFn<T> wrap(ASet<T> m) {
		return new SetFn<T>(m);
	}

	@Override
	public Context invoke(Context context, ACell[] args) {
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
	public boolean print(BlobBuilder sb,long limit) {
		return set.print(sb,limit);
	}

	@Override
	public ACell toCanonical() {
		return set.getCanonical();
	}

}
