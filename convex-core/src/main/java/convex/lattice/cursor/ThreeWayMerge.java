package convex.lattice.cursor;

import convex.core.data.ACell;

/**
 * Reconciles two values that changed independently from a common base. Having the
 * base shows which side changed what, which a merge of the two values alone cannot.
 *
 * @param <V> Value type
 */
@FunctionalInterface
public interface ThreeWayMerge<V extends ACell> {

	/**
	 * @param base Value both sides last agreed on, or null if there was none
	 * @param own This side's value
	 * @param other The other side's value
	 * @return Reconciled value
	 */
	V merge(V base,V own,V other);
}
