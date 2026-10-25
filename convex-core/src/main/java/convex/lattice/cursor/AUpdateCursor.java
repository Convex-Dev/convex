package convex.lattice.cursor;

import java.util.function.BiFunction;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.util.Utils;

/**
 * A transforming cursor which authors replacement storage from a view value.
 * {@link SignedCursor} signs payloads, {@link StampedCursor} stamps local edits,
 * and {@link DefaultCursor} presents an explicit default for absent storage.
 *
 * <p>Atomic operations and unchanged-write handling come from
 * {@link ATransformCursor}. This class adds operation-local preparation caching:
 * an unchanged candidate reuses its prepared cell across source CAS retries.
 * Nil clears storage without preparing. No cache is shared between operations.</p>
 *
 * @param <V> Viewed value type
 * @param <S> Stored value type
 */
public abstract class AUpdateCursor<V extends ACell,S extends ACell> extends ATransformCursor<V,S> {

	protected AUpdateCursor(ACursor<S> base) { super(base); }

	/**
	 * Authors storage for a changed, non-null value. May enforce authoring
	 * preconditions; must not mutate cursors or perform external side effects.
	 */
	protected abstract S prepareWrite(V value);

	/**
	 * Resolves authoring inputs on the first non-null change of one operation.
	 * Reused on retries, so a timestamp or similar input is resolved only once.
	 */
	protected Function<V,S> prepareWriteOperation() { return this::prepareWrite; }

	/** Identity read by default; representation-changing subclasses override it. */
	@SuppressWarnings("unchecked")
	@Override protected V view(S stored) { return (V)(ACell)stored; }

	@Override protected S write(S current,V value) {
		return value==null?null:prepareWrite(value);
	}

	@Override protected BiFunction<S,V,S> writeOperation() { return new PreparedWrite(); }

	/** Preparation depends on the new value, so source-only changes can reuse it. */
	private final class PreparedWrite implements BiFunction<S,V,S> {
		private V value;
		private S stored;
		private boolean present;
		private Function<V,S> prepare;

		@Override public S apply(S current,V newValue) {
			if (present && Utils.equals(newValue,value)) return stored;
			value=newValue;
			if (newValue==null) {
				stored=null;
			} else {
				if (prepare==null) prepare=prepareWriteOperation();
				stored=prepare.apply(newValue);
			}
			present=true;
			return stored;
		}
	}
}
