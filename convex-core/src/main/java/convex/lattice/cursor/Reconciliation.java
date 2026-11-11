package convex.lattice.cursor;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.util.Utils;

/**
 * One side's change, carried onto a destination that may have changed since the
 * base, as an update function the destination can retry.
 *
 * @param <V> Value type
 */
final class Reconciliation<V extends ACell> implements UnaryOperator<V> {
	private final ThreeWayMerge<V> merge;
	private V previous;
	private V candidate;

	/**
	 * @param base Destination value this side started from
	 * @param own This side's value
	 * @param merge Reconciliation where both sides changed
	 */
	Reconciliation(V base,V own,ThreeWayMerge<V> merge) {
		this.previous=base;
		this.candidate=own;
		this.merge=merge;
	}

	@Override public V apply(V other) {
		// Where only this side changed, candidate replaces other even if other is the
		// persisted form of the base: unless this side was persisted too, that puts
		// strong cells back over soft ones (see Persister).
		if (Utils.equals(candidate,previous) || Utils.equals(candidate,other)) {
			candidate=other;
		} else if (!Utils.equals(other,previous)) {
			candidate=merge.merge(previous,candidate,other);
		}
		previous=other;
		return candidate;
	}
}
