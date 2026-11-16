package convex.lattice.cursor;

import convex.core.data.ACell;

/**
 * Experimental. Reconciles two values that changed independently from a common base.
 * Having the base shows which side changed what, which a merge of the two values
 * alone cannot.
 *
 * <p>A merge is total: it is given every case, and a side that changed nothing
 * contributes nothing. The caller may skip it where the result is already known by
 * reference. Its result is built on {@code other}, the value held, so cells held
 * there, which may be persisted, stay held wherever the result equals them.</p>
 *
 * @param <V> Value type
 */
@FunctionalInterface
public interface ThreeWayMerge<V extends ACell> {

	/**
	 * @param base Value both sides last agreed on, or null if there was none
	 * @param own This side's value, built from base
	 * @param other The other side's value, which is held
	 * @return Reconciled value
	 */
	V merge(V base,V own,V other);
}
