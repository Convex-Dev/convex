package convex.core.lang.impl;

import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.util.BlobBuilder;

public class MapFn<K extends ACell, T  extends ACell> extends ADataFn<T> {

	private AMap<K, T> map;

	public MapFn(AMap<K, T> m) {
		this.map = m;
	}

	public static <K extends ACell, T extends ACell> MapFn<K, T> wrap(AMap<K, T> m) {
		return new MapFn<K, T>(m);
	}

	@SuppressWarnings({ "unchecked"})
	@Override
	public Context invoke(Context context, ACell[] args) {
		int n = args.length;
		T result;
		if (n == 1) {
			ACell key = args[0];
			result = (T) map.get(key);
		} else if (n == 2) {
			K key = (K) args[0];
			result = (T) map.get(key, args[1]);
		} else {
			return context.withArityError("Expected arity 1 or 2 for map lookup but got: " + n);
		}
		return context.withResult(result);
	}

	@Override
	public boolean print(BlobBuilder sb,long limit) {
		return map.print(sb,limit);
	}

	@Override
	public ACell toCanonical() {
		return map.getCanonical();
	}

}
