package convex.core.crypto.bc;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Ed25519Signature;
import convex.core.data.AArrayBlob;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.SignedData;

/**
 * Class representing an Ed25519 Key Pair using Sodium library
 * 
 */
public class BCKeyPair extends AKeyPair {

	private final AccountKey publicKey;
	private final Blob seed;
	
	/**
	 * Secret key in the specific format that Sodium expects, we wrap this complexity in this class
	 */
	protected final Ed25519PrivateKeyParameters priv;
	public static long signatureCount;

	private BCKeyPair(AccountKey pk, Blob seed, Ed25519PrivateKeyParameters priv) {
		this.publicKey=pk;
		this.seed=seed;
		this.priv=priv;
	}
	
	public static BCKeyPair create(Blob seed) {
		long n=seed.count();
		if (seed.count() != SEED_LENGTH) throw new IllegalArgumentException("32 bytes private key material expected as seed but got: "+n);

		Ed25519PrivateKeyParameters priv=new Ed25519PrivateKeyParameters(Blobs.zeroBasedArray(seed), 0);
		byte[] publicBytes=priv.generatePublicKey().getEncoded();
		AccountKey publicKey=AccountKey.wrap(publicBytes);
		
		return new BCKeyPair(publicKey,seed,priv);
	}

	@Override
	public Blob getSeed() {
		return seed;
	}

	@Override
	public AccountKey getAccountKey() {
		return publicKey;
	}

	@Override
	public <R extends ACell> SignedData<R> signData(R value) {
		return SignedData.sign(this, value);
	}

	@Override
	public ASignature sign(AArrayBlob hash) {		
		byte[] sigBytes=new byte[Ed25519Signature.SIGNATURE_LENGTH];
		Ed25519.sign(seed.getInternalArray(), seed.getInternalOffset(),hash.getInternalArray(), hash.getInternalOffset(),(int)hash.count(), sigBytes, 0);
		signatureCount++;
		return Ed25519Signature.wrap(sigBytes);
	}

	@Override
	public boolean equals(AKeyPair kp) {
		if (!(kp instanceof BCKeyPair)) return false;
		return equals((BCKeyPair) kp);
	}

	public boolean equals(BCKeyPair other) {
		if (!this.seed.equals(other.seed)) return false;
		if (!this.publicKey.equals(other.publicKey)) return false;
		return true;
	}

}
