package convex.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.PathCursor;

/**
 * A value location with literal string-key navigation. No key coercion, container
 * creation or merge rule is inferred. Parents select a specialised component,
 * such as {@link JSONValueComponent}, where another addressing policy is intended.
 * Resolved children share this component's editing view and publication boundary.
 *
 * @param <V> Value type at this location
 */
public class ValueComponent<V extends ACell> extends ALatticeComponent<V> {

	public ValueComponent(AForkableCursor<V> cursor) {
		super(cursor);
	}

	@Override
	public ValueComponent<?> resolve(AString[] path,int start,int end) {
		Objects.checkFromToIndex(start,end,path.length);
		if (start==end) return this;
		for (int i=start; i<end; i++) Objects.requireNonNull(path[i],"Path segment must not be null");
		return new ValueComponent<>(new PathCursor<ACell>(cursor(),path,start,end));
	}

	@Override protected ValueComponent<V> rebind(AForkableCursor<V> cursor) {
		return new ValueComponent<>(cursor);
	}

	@Override public ValueComponent<V> fork() {
		return rebind(storage.fork());
	}
}
