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
 * Base for signed domain views and their unsigned drafts. Domain operations use
 * the unsigned {@link #cursor()}. A live view validates and signs each changed edit
 * into its supplied destination using its explicit signer. Live instances over
 * the same path share edits immediately. Construction and reads create no state.
 *
 * <p>The owner-bound lattice supplies the same rule for local authoring and
 * incoming envelopes. Incoming merges preserve accepted signatures and cannot
 * navigate inside the envelope. A live view's sync only reads. A domain subclass
 * can implement {@link #rebind} to retain its API on an unsigned fork: edits then
 * stay private and sync signs the reconciled batch into the original destination.
 * Neither lifecycle publishes the containing map. {@link SignedComponent} supplies
 * an unsigned draft from construction when that is the intended lifecycle.</p>
 *
 * @param <V> Unsigned domain value
 */
public abstract class ASignedComponent<V extends ACell> extends ALatticeComponent<V> {

	private static final AString EXPECTED_ENVELOPE=Strings.intern("Expected a signed envelope");

	/** Signed storage for live envelope merging; unsigned drafts have no direct envelope destination. */
	private final ACursor<SignedData<V>> destination;
	/** Bound owner and payload rule retained when rebinding unsigned storage. */
	protected final SignedLattice<V> lattice;
	private final Function<V,SignedData<V>> signer;

	/** Constructs a domain view at a signed path with an explicit owner rule. */
	protected ASignedComponent(ACursor<SignedData<V>> destination,SignedLattice<V> lattice,
			Function<V,SignedData<V>> signer) {
		super(SignedCursor.create(Objects.requireNonNull(destination),value -> lattice.sign(value,signer)));
		this.destination=destination;
		this.lattice=Objects.requireNonNull(lattice);
		this.signer=Objects.requireNonNull(signer);
	}

	/**
	 * Binds unsigned working storage with the same owner rule. Rebound domains
	 * rebuild their editing views here. The supplied cursor determines publication;
	 * this constructor neither forks nor adds another signing boundary.
	 */
	protected ASignedComponent(SignedLattice<V> lattice,AForkableCursor<V> cursor) {
		super(cursor);
		this.lattice=Objects.requireNonNull(lattice);
		this.destination=null;
		this.signer=null;
	}

	/** Reconciles unsigned drafts using the payload policy bound to their owner. */
	@Override protected V mergePublication(V own,V other) {
		return lattice.getValueLattice().merge(own,other);
	}

	/**
	 * Checks envelope shape, then delegates to the optional {@link #checkPayload}
	 * diagnostic. Merge separately verifies the signature, owner and payload under
	 * its own acceptance policy; it does not invoke this diagnostic.
	 */
	@Override public AString checkValue(ACell value) {
		if (value==null) return null;
		if (!(value instanceof SignedData<?> signed)) return EXPECTED_ENVELOPE;
		return checkPayload(signed.getValue());
	}

	/** Optional domain compatibility check. Receives the untrusted payload without a generic cast. */
	protected AString checkPayload(ACell value) {
		return null;
	}

	/**
	 * Verifies a complete envelope. Live views preserve selected signatures or sign
	 * a synthesised payload; unsigned drafts merge privately without signing or
	 * re-stamping. A draft never writes directly to the original signed destination.
	 */
	public V merge(SignedData<V> incoming) {
		if (destination!=null) {
			SignedData<V> result=destination.updateAndGet(own -> lattice.mergeAndSign(own,incoming,signer));
			return result==null?null:result.getValue();
		}
		if (incoming==null || lattice.merge(null,incoming)==null) return storage.get();
		return storage.updateAndGet(own -> lattice.getValueLattice().merge(own,incoming.getValue()));
	}

	/** Incoming delivery stops at the signed boundary; interior paths introduce no data. */
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
