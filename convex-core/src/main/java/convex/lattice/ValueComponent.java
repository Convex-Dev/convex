package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.Path;
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
	public ValueComponent<?> resolve(Path path) {
		if (path.isEmpty()) return this;
		return new ValueComponent<>(new LiteralPath(cursor(),path));
	}

	/** A literal path whose refused writes name the caller's segment. */
	private static final class LiteralPath extends PathCursor<ACell> {
		private final Path path;

		LiteralPath(AForkableCursor<?> base,Path path) {
			super(base,path.toCellArray());
			this.path=path;
		}

		@Override protected ACell write(ACell current,ACell value) {
			try {
				return super.write(current,value);
			} catch (LatticeOps.AbsentContainer | LatticeOps.UnholdableKey e) {
				throw LatticeOps.refusal(e,path);
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
