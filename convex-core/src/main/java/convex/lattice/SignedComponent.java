package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.core.util.Utils;
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
 * <p>Sync stops at the destination cursor. An application can checkpoint its
 * owned components in {@link ALatticeApplication#beforeSync()} before publishing
 * its own working state. This component retains no parent component or store.</p>
 *
 * @param <V> Unsigned working value
 */
public final class SignedComponent<V extends ACell> extends ALatticeComponent<V> {
	private final SignedLattice<V> lattice;

	/**
	 * Creates unsigned working state over a signed destination. The lattice should
	 * be the owner-bound instance from the destination's lattice definition, such as
	 * {@code owners.signingFor(owner)}. Signing policy is resolved from the
	 * component's explicit policy, configured before publication.
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

	/**
	 * Reconciles already-admitted signed snapshots for enclosing application
	 * publication, without changing this component's working state. Local edits
	 * are own on ties; unchanged local branches adopt the destination. Existing
	 * envelopes are reused when selected, otherwise the combined payload is signed
	 * here. Missing signing authority fails publication rather than losing a branch.
	 */
	public SignedData<V> mergePublication(SignedData<V> baseline,SignedData<V> own,SignedData<V> other) {
		V base=value(baseline);
		V local=value(own);
		V remote=value(other);
		if (Utils.equals(local,base)) return other;
		if (Utils.equals(remote,base) || Utils.equals(local,remote)) return own;
		V merged=lattice.getValueLattice().merge(getContext(),local,remote);
		if (Utils.equals(merged,local)) return own;
		if (Utils.equals(merged,remote)) return other;
		if (merged==null) return null;
		SignedData<V> signed=lattice.sign(getContext(),merged);
		if (signed==null) throw new IllegalStateException(
			"No authorised signer available for component owner "+lattice.getOwner());
		return signed;
	}

	private static <T extends ACell> T value(SignedData<T> signed) {
		return (signed==null)?null:signed.getValue();
	}
}
