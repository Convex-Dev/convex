package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.SignedData;

/**
 * The rule for authoring one signed envelope, as the signing cursor needs it: how to
 * sign a value for the owner of this position, who that owner is, and the shape of
 * the unsigned value inside.
 *
 * <p>{@code SignedLattice} implements this with the owner-bound verification rule the
 * host applies to incoming merges, so authoring and verification cannot disagree.</p>
 *
 * @param <V> Unsigned value type
 */
public interface Signing<V extends ACell> {

	/**
	 * Signs a value for this position's owner using a signer the context policy
	 * authorises, or returns null when no authorised signer is available.
	 *
	 * @param context Write policy supplying signing capability
	 * @param value Unsigned value
	 * @return Signed envelope, or null
	 */
	SignedData<V> sign(LatticeContext context, V value);

	/**
	 * Returns the owner identity this position belongs to, or null when unbound.
	 *
	 * @return Owner identity, or null
	 */
	ACell getOwner();

	/**
	 * Returns the shape of the unsigned value inside the envelope.
	 *
	 * @return Value shape, never null
	 */
	Shape<V> getValueShape();
}
