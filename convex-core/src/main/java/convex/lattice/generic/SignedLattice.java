package convex.lattice.generic;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ABlob;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Lattice node representing signed Data.
 * 
 * Merges that produce new values will fail if no keypair is set.
 * 
 * @param <V> Type of signed lattice value
 */
public class SignedLattice<V extends ACell> extends ALattice<SignedData<V>> {

	
	protected final ALattice<V> valueNode;
	private AKeyPair keyPair;

	private SignedLattice(ALattice<V> valueNode) {
		this.valueNode=valueNode;
	}
	
	public static <V extends ACell> SignedLattice<V> create(ALattice<V> childNode) {
		return new SignedLattice<>(childNode);
	}
	
	@Override
	public SignedData<V> merge(SignedData<V> ownValue, SignedData<V> otherValue) {
		// Delegate to context-aware merge with fallback context
		LatticeContext ctx = (keyPair != null)
			? LatticeContext.create(null, keyPair)
			: LatticeContext.EMPTY;
		return merge(ctx, ownValue, otherValue);
	}

	@Override
	public SignedData<V> merge(LatticeContext context, SignedData<V> ownValue, SignedData<V> otherValue) {
		if (otherValue==null) return ownValue;

		// If we don't have a value, use other as long as signature is correct
		if (ownValue==null) {
			if (checkForeign(otherValue)) return otherValue;
			return null;
		}

		// Fast path for identical values (common case after null checks)
		V a=ownValue.getValue();
		V b=otherValue.getValue();
		if (Utils.equals(a,b)) return ownValue;

		// Bail out if other signature is invalid
		if (!checkForeign(otherValue)) return ownValue;

		// Perform child lattice merge with context
		V m=valueNode.merge(context, a, b);

		// Check if new lattice value is identical to either input
		if (Utils.equals(a, m)) return ownValue;
		if (Utils.equals(b, m)) return otherValue;

		return sign(context, m);
	}

	@Override
	public boolean checkForeign(SignedData<V> otherValue) {
		if (otherValue==null) return false;
		return otherValue.checkSignature();
	}

	private SignedData<V> sign(V m) {
		AKeyPair kp=getKeyPair();

		if (kp==null) throw new IllegalStateException("Unable to sign new lattice value");

		return kp.signData(m);
	}

	private SignedData<V> sign(LatticeContext context, V m) {
		// Try to get keypair from context first, fall back to instance variable
		AKeyPair kp = context.getSigningKey();
		if (kp == null) kp = getKeyPair();

		if (kp==null) throw new IllegalStateException("Unable to sign new lattice value");

		return kp.signData(m);
	}

	public AKeyPair getKeyPair() {
		return keyPair;
	}
	
	public void setKeyPair(AKeyPair keyPair) {
		this.keyPair=keyPair;
	}

	@Override
	public SignedData<V> zero() {
		return null;
	}

	private static final ABlob VALUE_BLOB = Keywords.VALUE.toBlob();

	@Override
	public ACell resolveKey(ACell key) {
		if (key instanceof ABlobLike<?> blobLike) {
			if (VALUE_BLOB.equals(blobLike.toBlob())) {
				return Keywords.VALUE;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		if (Keywords.VALUE.equals(childKey)) {
			return (ALattice<T>) valueNode;
		}
		return null;
	}

	

}
