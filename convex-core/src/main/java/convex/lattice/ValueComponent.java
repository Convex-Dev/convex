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
 * A write through a resolved path whose container is absent, or cannot hold its key,
 * throws {@link NoSuchPathException} at that segment.
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
		return new ValueComponent<>(new LiteralPath(cursor(),path,start,end));
	}

	/** A literal path whose refused writes name the caller's segment. */
	private static final class LiteralPath extends PathCursor<ACell> {
		private final AString[] segments;
		private final int start;

		LiteralPath(AForkableCursor<?> base,AString[] path,int start,int end) {
			super(base,path,start,end);
			this.segments=path.clone();
			this.start=start;
		}

		@Override protected ACell write(ACell current,ACell value) {
			try {
				return super.write(current,value);
			} catch (LatticeOps.AbsentContainer | LatticeOps.UnholdableKey e) {
				throw LatticeOps.refusal(e,segments,start);
			}
		}
	}

	@Override protected ValueComponent<V> rebind(AForkableCursor<V> cursor) {
		return new ValueComponent<>(cursor);
	}

	@Override public ValueComponent<V> fork() {
		return rebind(storage.fork());
	}
}
