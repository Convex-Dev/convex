package convex.lattice;

import java.util.Objects;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.generic.SignedLattice;

/**
 * Base for domain components at a signed storage path. Domain operations use the
 * unsigned {@link #cursor()}; each changed edit is validated and signed into the
 * supplied destination using its explicitly supplied signer. Instances over
 * the same path share edits immediately. Construction and reads create no state.
 *
 * <p>The owner-bound lattice supplies the same rule for local authoring and
 * incoming envelopes. Incoming merges preserve accepted signatures and cannot
 * navigate inside the envelope. Sync returns the current unsigned value without
 * publishing the containing map. For an independent unsigned draft which signs
 * only on sync, use {@link SignedComponent} instead.</p>
 *
 * @param <V> Unsigned domain value
 */
public abstract class ASignedComponent<V extends ACell> extends ALatticeComponent<V> {

	private final ACursor<SignedData<V>> destination;
	private final SignedLattice<V> lattice;
	private final Function<V,SignedData<V>> signer;

	/** Constructs a domain view at a signed path with an explicit owner rule. */
	protected ASignedComponent(ACursor<SignedData<V>> destination,SignedLattice<V> lattice,
			Function<V,SignedData<V>> signer) {
		super(SignedCursor.create(Objects.requireNonNull(destination),value -> lattice.sign(value,signer)));
		this.destination=destination;
		this.lattice=Objects.requireNonNull(lattice);
		this.signer=Objects.requireNonNull(signer);
	}

	/** Verifies and merges a complete envelope, retaining its signature when selected. */
	public V merge(SignedData<V> incoming) {
		SignedData<V> result=destination.updateAndGet(own -> lattice.mergeAndSign(own,incoming,signer));
		return result==null?null:result.getValue();
	}

	/** Incoming delivery stops at the signed boundary; interior paths introduce no data. */
	@SuppressWarnings("unchecked")
	@Override
	public V mergeAt(ACell incoming,ACell... path) {
		if (path.length!=0) return cursor.get();
		if (incoming!=null && !(incoming instanceof SignedData<?>)) {
			throw new IllegalArgumentException("Signed component requires a complete envelope");
		}
		return merge((SignedData<V>)incoming);
	}
}
