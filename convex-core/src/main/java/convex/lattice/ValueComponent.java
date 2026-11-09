package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Path;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.PathCursor;

/**
 * A value location with literal string-key navigation. No key coercion, container
 * creation or merge rule is inferred. Parents select a specialised component,
 * such as {@link JSONValueComponent}, where another addressing policy is intended.
 * Resolved children share this component's editing view and publication boundary,
 * and keep only their keys, in a {@link PathCursor}. A write through one whose
 * container is absent, or cannot hold its key, throws {@link PathWriteException}
 * at that key.
 *
 * @param <V> Value type at this location
 */
public class ValueComponent<V extends ACell> extends ALatticeComponent<V> {

	public ValueComponent(AForkableCursor<V> cursor) {
		super(cursor);
	}

	@Override
	public ValueComponent<?> resolve(AString... segments) {
		return (ValueComponent<?>)super.resolve(segments);
	}

	@Override
	public ValueComponent<?> resolve(Path path) {
		return (ValueComponent<?>)super.resolve(path);
	}

	/** Uses each segment as a literal key, without reading the current value. */
	@Override
	public ValueComponent<?> resolve(AString[] segments,int start,int end) {
		if (start==end) return this;
		return new ValueComponent<>(new PathCursor<ACell>(cursor(),segments,start,end));
	}

	@Override public ValueComponent<V> rebind(AForkableCursor<V> cursor) {
		return new ValueComponent<>(cursor);
	}

	@Override public ValueComponent<V> fork() {
		return rebind(storage.fork());
	}
}
