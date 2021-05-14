package convex.core.lang.impl;

import convex.core.data.ACell;
import convex.core.data.IGet;
import convex.core.data.Keyword;
import convex.core.lang.Context;
import convex.core.lang.RT;

public class KeyFn<T extends ACell> extends ADataFn<T> {
	private Keyword key;

	public KeyFn(Keyword k) {
		this.key = k;
	}

	public static <T extends ACell> KeyFn<T> wrap(Keyword k) {
		return new KeyFn<T>(k);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public Context<T> invoke(Context context, ACell[] args) {
		int n = args.length;
		T result;
		if (n == 1) {
			IGet<T> gettable = RT.toGettable(args[0]);
			if (gettable == null) return context.withCastError(args[0], IGet.class);
			result = gettable.get(key);
		} else if (n == 2) {
			ACell ds = args[0];
			ACell notFound = args[1];
			if (ds == null) {
				result = (T) notFound;
			} else {
				IGet<T> gettable = RT.toGettable(ds);
				if (gettable == null) return context.withCastError(ds, IGet.class);
				result = (T) RT.get(gettable, key, notFound);
			}
		} else {
			return context.withArityError("Expected arity 1 or 2 for keyword lookup but got: " + n);
		}
		return context.withResult(result);
	}

	@Override
	public void ednString(StringBuilder sb) {
		key.ednString(sb);
	}

	@Override
	public void print(StringBuilder sb) {
		key.print(sb);
	}
}
