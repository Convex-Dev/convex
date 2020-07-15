package convex.core.lang.impl;

import convex.core.data.ASequence;
import convex.core.lang.Context;
import convex.core.lang.IFn;
import convex.core.lang.RT;

/**
 * Wrapper for interpreting a sequence object as an invokable function
 * 
 * 
 * @param <T> Type of values to return
 */
public class SeqFn<T> implements IFn<T> {

	private ASequence<?> seq;

	public SeqFn(ASequence<?> m) {
		this.seq = m;
	}

	public static <T> SeqFn<T> wrap(ASequence<?> m) {
		return new SeqFn<T>(m);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <I> Context<T> invoke(Context<I> context, Object[] args) {
		int n = args.length;
		if (n == 1) {
			long key = RT.toLong(args[0]);
			T result = (T) seq.get(key);
			return context.withResult(result);
		} else if (n == 2) {
			long key = RT.toLong(args[0]);
			if ((key < 0) || (key > seq.count())) return (Context<T>) context.withResult(args[1]);
			T result = (T) seq.get(key);
			return context.withResult(result);
		} else {
			return context.withArityError("Expected arity 1 for sequence lookup");
		}
	}

}
