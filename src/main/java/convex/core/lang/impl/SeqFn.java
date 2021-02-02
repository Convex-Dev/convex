package convex.core.lang.impl;

import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.prim.CVMLong;
import convex.core.lang.Context;
import convex.core.lang.IFn;
import convex.core.lang.RT;

/**
 * Wrapper for interpreting a sequence object as an invokable function
 * 
 * 
 * @param <T> Type of values to return
 */
public class SeqFn<T extends ACell> implements IFn<T> {

	private ASequence<?> seq;

	public SeqFn(ASequence<?> m) {
		this.seq = m;
	}

	public static <T extends ACell> SeqFn<T> wrap(ASequence<?> m) {
		return new SeqFn<T>(m);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public Context<T> invoke(Context context, Object[] args) {
		int n = args.length;
		if (n == 1) {
			CVMLong key = RT.toLong(args[0]);
			if (key==null) return context.withCastError(args[0], Long.class);
			long ix=key.longValue();
			if ((ix < 0) || (ix >= seq.count())) return (Context<T>) context.withBoundsError(ix);
			T result = (T) seq.get(key);
			return context.withResult(result);
		} else if (n == 2) {
			CVMLong key = RT.toLong(args[0]);
			if (key==null) return context.withCastError(args[0], Long.class);
			long ix=key.longValue();
			if ((ix < 0) || (ix >= seq.count())) return (Context<T>) context.withResult((T)args[1]);
			T result = (T) seq.get(key);
			return context.withResult(result);
		} else {
			return context.withArityError("Expected arity 1 or 2 for sequence lookup");
		}
	}

}
