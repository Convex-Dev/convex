package convex.lattice.cursor;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.util.Utils;

/**
 * One side's change, carried onto a destination that may have changed since the
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
		// Where only this side changed, own replaces other even if other is the
		// persisted form of the base: unless this side was persisted too, that puts
		// strong cells back over soft ones (see Persister).
		if (Utils.equals(own,base) || Utils.equals(own,other)) {
			own=other;
		} else if (!Utils.equals(other,base)) {
			own=merge.merge(base,own,other);
		}
		base=other;
		return own;
	}
}
