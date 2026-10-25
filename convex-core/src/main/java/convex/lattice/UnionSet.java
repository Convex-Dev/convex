package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.SetLattice;

/**
 * Set operations with union for incoming values and publication conflicts.
 * Construction binds supplied storage; {@link #fork()} creates a private draft.
 * Local removals are supported, but union can restore removed members from another
 * replica. Replicated deletion requires a different policy or a containing record.
 *
 * @param <V> Set element type, with no runtime member schema
 */
public class UnionSet<V extends ACell> extends SetComponent<V> {

	public UnionSet(AForkableCursor<ASet<V>> cursor) { super(cursor); }

	@Override protected UnionSet<V> rebind(AForkableCursor<ASet<V>> cursor) {
		return new UnionSet<>(cursor);
	}

	@Override public UnionSet<V> fork() { return rebind(storage.fork()); }

	@Override public ASet<V> merge(ASet<V> incoming) {
		return storage.updateAndGet(own -> SetLattice.<V>create().merge(own,incoming));
	}

	@Override protected ASet<V> mergePublication(ASet<V> own,ASet<V> other) {
		return SetLattice.<V>create().merge(own,other);
	}
}
