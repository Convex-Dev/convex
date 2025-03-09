package convex.core.lattice;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.core.util.Utils;

/**
 * Lattice node representing signed Data.
 * 
 * Merges that produce new values will fail if no keypair is set.
 * 
 * @param <V>
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
		if (otherValue==null) return ownValue;
		
		// If we don't value a value, use other as long as signature is correct
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
		
		// Perform child lattice merge
		V m=valueNode.merge(a, b);
		
		// Check if new lattice value is identical to either input
		if (Utils.equals(a, m)) return ownValue;
		if (Utils.equals(b, m)) return otherValue;
		
		return sign(m);
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

	

}
