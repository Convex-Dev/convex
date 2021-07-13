package convex.core.data;

import convex.core.util.MergeFunction;

public abstract class AHashSet<T extends ACell> extends ASet<T> {

	protected AHashSet(long count) {
		super(count);
	}

	protected abstract ASet<T> mergeWith(ASet<T> b, MergeFunction<T> func, int shift);

	public abstract AHashSet<T> mergeWith(AHashSet<T> b, MergeFunction<T> func);

	public abstract AHashSet<T> mergeDifferences(AHashSet<T> b, MergeFunction<T> func);

	protected abstract AHashSet<T> mergeDifferences(AHashSet<T> b, MergeFunction<T> func, int shift);

	public abstract AHashSet<T> excludeRef(Ref<T> keyRef);

}
