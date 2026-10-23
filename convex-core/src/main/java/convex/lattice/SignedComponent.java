package convex.lattice;

import java.util.Objects;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.SignedCursor;
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
 * <p>This component composes an ordinary fork with a {@link SignedCursor} over its
 * destination. The fork reconciles unsigned values; the cursor signs on publication.
 * Sync stops there. The destination may be a host path or a slot in an owned map's working
 * state. In the latter case the map must subsequently sync to publish outward;
 * syncing the map never implicitly publishes this draft. This component retains
 * no parent component or store.</p>
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
	 * such as {@code owners.signingFor(owner)}. The supplied signer is used for
	 * publication and re-signing; a dynamic signer may resolve keys when called.
	 * For a domain view which signs each atomic edit immediately, extend
	 * {@link ASignedComponent} over the destination instead.
	 */
	public SignedComponent(SignedLattice<V> lattice, ACursor<SignedData<V>> destination,
			Function<V,SignedData<V>> signer) {
		this(lattice,SignedCursor.create(destination,value -> lattice.sign(value,signer)).fork());
		Objects.requireNonNull(signer,"Signer must not be null");
	}

	/**
	 * Binds existing unsigned working storage, including a transaction draft or a
	 * nested fork. Signing remains at the original signed destination, not at each
	 * intermediate unsigned commit.
	 */
	protected SignedComponent(SignedLattice<V> lattice,AForkableCursor<V> cursor) {
		super(cursor);
		this.lattice=Objects.requireNonNull(lattice);
	}

	@Override protected SignedComponent<V> rebind(AForkableCursor<V> cursor) {
		return new SignedComponent<>(lattice,cursor);
	}

	@Override public SignedComponent<V> fork() { return rebind(storage.fork()); }

	/**
	 * Checks envelope shape only. Merge separately verifies the signature, owner
	 * and payload; subclasses may compose domain checks over the unsigned value.
	 */
	@Override public AString checkValue(ACell value) {
		return value==null || value instanceof SignedData<?>?null:
			Strings.create("Expected a signed envelope");
	}

	@Override
	protected V mergePublication(V own,V other) {
		return lattice.getValueLattice().merge(own,other);
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
		if (incoming==null || lattice.merge(null,incoming)==null) return cursor().get();
		return cursor().updateAndGet(own -> lattice.getValueLattice().merge(own,incoming.getValue()));
	}

	/**
	 * Receives a complete envelope at this boundary. No incoming path can cross
	 * inside the envelope; unsigned edits use the working cursor instead. This
	 * entry point also serves deliveries from {@link AKeyedRegions}.
	 */
	@SuppressWarnings("unchecked")
	@Override
	public V mergeAt(ACell incoming,ACell... path) {
		if (path.length!=0) return cursor().get();
		if (incoming!=null && !(incoming instanceof SignedData<?>)) {
			throw new IllegalArgumentException("Signed component requires a complete envelope");
		}
		return merge((SignedData<V>)incoming);
	}
}
