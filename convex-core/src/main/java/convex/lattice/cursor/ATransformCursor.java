package convex.lattice.cursor;

import java.util.Objects;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.util.Utils;

/**
 * Atomic mutable view over another cursor. Subclasses read a view value and
 * rebuild source storage on a changed write; this class supplies the operations.
 * Unchanged writes preserve the source, including absent storage.
 *
 * <p>A transform behaves as any cursor does for its consumers. It is absent where it
 * finds no value: where its source is absent, or where its view finds nothing in a
 * stored value. A view that should hold a value when nothing is stored is built
 * over a source with a default ({@link AForkableCursor#withDefault}). A transform
 * creates no storage by default: its write receives null for absent storage, and
 * a navigating view refuses a missing container with
 * {@link convex.lattice.PathWriteException}. A subclass whose definition knows how
 * to build its storage, as a default, an authoring view or a component's
 * projection does, builds it in its write.</p>
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

	/**
	 * Reads presence and value from one stored source value; an absent source is
	 * absent here without this being called. A view that finds nothing at its place
	 * in the stored value returns {@link #MISSING}; otherwise it returns its value,
	 * including nil. This is for reads; updates use {@link #view} for their old value.
	 */
	protected Object viewValue(S stored) { return view(stored); }

	/** Rebuilds source storage for a changed view value, including nil. */
	protected abstract S write(S current,V value);

	@Override protected Claim claim(Function<? super V,? extends ACell> reader) {
		return base.claim(stored -> reader.apply(view(stored)));
	}

	@Override public V get() { return CursorValues.require(readValue()); }

	@SuppressWarnings("unchecked")
	@Override protected Object readValue() {
		Object stored=base.readValue();
		return stored==MISSING?MISSING:viewValue((S)stored);
	}

	/** Builds one source update, which leaves the source as it is when the view is unchanged. */
	private UnaryOperator<S> sourceUpdate(UnaryOperator<V> action) {
		return current -> {
			V previous=view(current);
			V next=action.apply(previous);
			return Utils.equals(previous,next)?current:write(current,next);
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
		S current=base.getOrDefault(null);
		if (!Utils.equals(expected,view(current))) return false;
		return base.compareAndSet(current,sourceUpdate(previous -> value).apply(current));
	}
}
