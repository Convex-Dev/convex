package convex.lattice.cursor;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.util.Utils;

/**
 * Atomic mutable view over another cursor. Subclasses read a view value and
 * rebuild source storage on a changed write; this class supplies the operations.
 * Unchanged writes preserve the source, including absent storage.
 *
 * <p>Read and write functions must be side-effect free and may run again on a
 * conflict. The read function must accept values produced by both this view and
 * its source's write policy. Writes must preserve unrelated source state.</p>
 *
 * @param <V> Viewed value type
 * @param <S> Source value type
 */
public abstract class ATransformCursor<V extends ACell,S extends ACell> extends AForkableCursor<V> {

	protected final ACursor<S> base;

	protected ATransformCursor(ACursor<S> base) {
		this.base=Objects.requireNonNull(base);
	}

	/** Reads the view from source storage, including nil. */
	protected abstract V view(S stored);

	/** Rebuilds source storage for a changed view value, including nil. */
	protected abstract S write(S current,V value);

	/** One write operation; subclasses may retain preparation across its retries. */
	protected BiFunction<S,V,S> writeOperation() { return this::write; }

	@Override protected Lock exclusiveLock() { return base.exclusiveLock(); }
	@Override public V get() { return view(base.get()); }

	/** Builds one source update, with preparation shared across retries. */
	private UnaryOperator<S> sourceUpdate(UnaryOperator<V> action) {
		BiFunction<S,V,S> writer=writeOperation();
		return current -> {
			V previous=view(current);
			V next=action.apply(previous);
			return Utils.equals(previous,next)?current:writer.apply(current,next);
		};
	}

	@Override public void set(V value) { base.getAndUpdate(sourceUpdate(previous -> value)); }
	@Override public V getAndSet(V value) { return getAndUpdate(previous -> value); }

	@Override public V getAndUpdate(UnaryOperator<V> action) {
		return view(base.getAndUpdate(sourceUpdate(action)));
	}

	@Override public V updateAndGet(UnaryOperator<V> action) {
		return view(base.updateAndGet(sourceUpdate(action)));
	}

	@Override public V getAndAccumulate(V value,BinaryOperator<V> action) {
		return getAndUpdate(current -> action.apply(current,value));
	}

	@Override public V accumulateAndGet(V value,BinaryOperator<V> action) {
		return updateAndGet(current -> action.apply(current,value));
	}

	/** One source CAS attempt, comparing the view by value equality. */
	@Override public boolean compareAndSet(V expected,V value) {
		S current=base.get();
		if (!Utils.equals(expected,view(current))) return false;
		return base.compareAndSet(current,sourceUpdate(previous -> value).apply(current));
	}
}
