package convex.core.lang.impl;

import convex.core.data.AMap;
import convex.core.lang.Context;
import convex.core.lang.IFn;

public class MapFn<K, T> implements IFn<T> {

	private AMap<K, T> map;

	public MapFn(AMap<K, T> m) {
		this.map = m;
	}

	public static <K, T> MapFn<K, T> wrap(AMap<K, T> m) {
		return new MapFn<K, T>(m);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <I> Context<T> invoke(Context<I> context, Object[] args) {
		int n = args.length;
		T result;
		if (n == 1) {
			Object key = args[0];
			result = map.get(key);
		} else if (n == 2) {
			K key = (K) args[0];
			result = map.get(key, (T) args[1]);
		} else {
			return context.withArityError("Expected arity 1 or 2 for map lookup but got: " + n);
		}
		return context.withResult(result);
	}

}
