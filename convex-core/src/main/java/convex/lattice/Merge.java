package convex.lattice;

import convex.core.data.ACell;

/**
 * A context-aware merge function over values at one lattice position.
 *
 * <p>Merge is ordered: {@code own} is the value whose precedence is retained on a
 * tie. Every {@link ALattice} is a merge function, used by components
 * when admitting incoming state or reconciling publication conflicts.</p>
 *
 * @param <V> Value type
 */
@FunctionalInterface
public interface Merge<V extends ACell> {

	/**
	 * Merges two values under the supplied write policy.
	 *
	 * @param context Policy supplying clock, signers and verification
	 * @param own Current value, retained on a tie
	 * @param other Incoming value
	 * @return Merged value
	 */
	V merge(LatticeContext context, V own, V other);
}
