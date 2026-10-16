package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.lattice.cursor.ACursor;
import convex.lattice.generic.SignedLattice;

/**
 * An owned unsigned working subtree which publishes a signed envelope.
 *
 * <p>Lightweight domain components use this component's working cursor. Their
 * updates do not sign or publish. Sync reconciles a snapshot with the destination
 * using the unsigned lattice and signs the result outside the destination CAS.
 * An unrelated ancestor write reuses the prepared signature; a conflict changing
 * the payload may require another signature. An unchanged publication does not
 * sign. The bound lattice supplies both the merge and the authorisation rule.</p>
 *
 * <p>Sync publishes through this component's own fork of its destination and stops
 * there. Give it a path in the host, not a path inside another staged component's
 * working copy; an application syncs its signed components in
 * {@link ALatticeApplication#beforeSync()} before publishing root-level state of
 * its own. This component retains no parent component or store.</p>
 *
 * <p>Domain components may extend this class to add operations directly over
 * {@link #cursor()}, inheriting verification, batched signing and publication.
 * Choose this boundary explicitly when edits should remain unsigned until sync.</p>
 *
 * @param <V> Unsigned working value
 */
public class SignedComponent<V extends ACell> extends ALatticeComponent<V> {
	private final SignedLattice<V> lattice;

	/**
	 * Creates private unsigned working state over a signed destination. The lattice
	 * should be the owner-bound instance from the destination's lattice definition,
	 * such as {@code owners.signingFor(owner)}. Signing policy is resolved from the
	 * component's explicit policy, configured before publication.
	 * For a lightweight view which signs each atomic edit immediately, compose a
	 * {@link convex.lattice.cursor.SignedCursor} with the destination instead.
	 */
	public SignedComponent(SignedLattice<V> lattice, ACursor<SignedData<V>> destination) {
		super(lattice.getValueLattice(),destination,
			stored -> (stored==null)?null:stored.getValue(),
			(context,value) -> {
				if (value==null) return null;
				SignedData<V> signed=lattice.sign(context,value);
				if (signed==null) throw new IllegalStateException(
					"No authorised signer available for component owner "+lattice.getOwner());
				return signed;
			});
		this.lattice=lattice;
	}

	/**
	 * Verifies an incoming owner envelope and atomically merges its payload into
	 * working state. Invalid signatures and unauthorised signers are ignored.
	 * Signing and publication remain deferred until sync. The caller must also
	 * verify any enclosing signed envelopes before routing an interior value here.
	 *
	 * @return Current unsigned value after merging, or unchanged on rejection
	 */
	public V merge(SignedData<V> incoming) {
		if (incoming==null || !lattice.checkForeign(incoming)) return cursor.get();
		return cursor.updateAndGet(own -> lattice.getValueLattice().merge(getContext(),own,incoming.getValue()));
	}
}
