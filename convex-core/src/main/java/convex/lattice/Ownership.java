package convex.lattice;

import convex.core.data.ACell;

/**
 * An owner-keyed level, as the component that owns it needs to cross into one
 * owner's slot: the signing rule for that owner, carrying the same verification
 * the level applies to incoming merges.
 *
 * <p>Every owner lattice implements this; a component obtains the bound rule with
 * {@code signingFor(owner)} and supplies an explicit authoring function to its view.</p>
 *
 * @param <V> Unsigned value type of each owner's slot
 */
public interface Ownership<V extends ACell> {

	/**
	 * Returns the signing rule for one owner's slot, or null if the key is not a
	 * valid owner for this level.
	 *
	 * @param owner Owner key, in any representation this level resolves
	 * @return Owner-bound signing rule, or null
	 */
	Signing<V> signingFor(ACell owner);
}
