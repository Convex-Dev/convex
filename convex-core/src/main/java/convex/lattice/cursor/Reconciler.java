package convex.lattice.cursor;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;

/**
 * One side's change, reconciled with a destination that may have changed since the
 * base, as an update function the destination can retry. Each application is given
 * the destination's current value as {@code other}.
 *
 * @param <V> Value type
 */
final class Reconciler<V extends ACell> implements UnaryOperator<V> {
	private final ThreeWayMerge<V> merge;
	/** Destination value this side's change applies to: at first the one it started from, then the last one reconciled. */
	private V base;
	/** This side's value, then the last value reconciled for the destination. */
	private V own;

	/**
	 * @param base Destination value this side started from
	 * @param own This side's value
	 * @param merge Reconciliation where both sides changed
	 */
	Reconciler(V base,V own,ThreeWayMerge<V> merge) {
		this.base=base;
		this.own=own;
		this.merge=merge;
	}

	@Override public V apply(V other) {
		own=reconcile(base,own,other,merge);
		base=other;
		return own;
	}

	/** Reconciles by the merge, skipping it where the result is known by reference, which costs nothing. */
	static <V extends ACell> V reconcile(V base,V own,V other,ThreeWayMerge<V> merge) {
		if (other==base) return own;
		if (own==base) return other;
		return merge.merge(base,own,other);
	}
}
