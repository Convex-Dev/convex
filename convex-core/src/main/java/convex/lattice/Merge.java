package convex.lattice;

import convex.core.data.ACell;

/**
 * A merge function over values at one lattice position, as a cursor needs it: a
 * fork supplies one at fork time so that {@code sync()} converges with the parent
 * instead of overwriting it, and a host supplies one so that root publication can
 * resolve a concurrent write.
 *
 * <p>Merge is ordered: {@code own} is the value whose precedence is retained on a
 * tie. Every {@link ALattice} is a merge function, so a component passes its
 * declared lattice wherever a cursor asks for one.</p>
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
