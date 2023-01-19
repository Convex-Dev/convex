package convex.core.crypto.sodium;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Ed25519Signature;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;

/**
 * Class representing an Ed25519 Key Pair using Sodium library
 * 
 */
public class SodiumKeyPair extends AKeyPair {
	private static final int SECRET_LENGTH=64;

	private final AccountKey publicKey;
	private final Blob seed;
	
	/**
	 * Secret key in the specific format that Sodium expects, we wrap this complexity in this class
	 */
	private final byte[] secretKeyBytes;

	private SodiumKeyPair(AccountKey pk, Blob seed, byte[] skBytes) {
		this.publicKey=pk;
		this.seed=seed;
		this.secretKeyBytes=skBytes;
	}
	
	public static SodiumKeyPair create(Blob seed) {
		long n=seed.count();
		if (seed.count() != SEED_LENGTH) throw new IllegalArgumentException("32 bytes private key material expected as seed but got: "+n);

		byte[] secretKeyBytes=new byte[SECRET_LENGTH];
		byte[] pkBytes=new byte[AccountKey.LENGTH];
		SodiumProvider.SODIUM_SIGN.cryptoSignSeedKeypair(pkBytes, secretKeyBytes, seed.getBytes());
		AccountKey publicKey=AccountKey.wrap(pkBytes);
		return new SodiumKeyPair(publicKey,seed,secretKeyBytes);
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
		return SignedData.create(this, value);
	}

	@Override
	public ASignature sign(Hash hash) {
		byte[] signature=new byte[Ed25519Signature.SIGNATURE_LENGTH];
		if (SodiumProvider.SODIUM_SIGN.cryptoSignDetached(
				signature,
				hash.getBytes(),
				Hash.LENGTH,
				secretKeyBytes)) {;
				return Ed25519Signature.wrap(signature);
		} else {
			throw new Error("Signing failed!");
		}

//		try {
//			Signature signer = Signature.getInstance(ED25519);
//			signer.initSign(getPrivate());
//			signer.update(hash.getInternalArray(), hash.getInternalOffset(), Hash.LENGTH);
//			byte[] signature = signer.sign();
//			return Ed25519Signature.wrap(signature);
//		} catch (GeneralSecurityException e) {
//			throw new Error(e);
//		}
	}

	@Override
	public boolean equals(AKeyPair kp) {
		if (!(kp instanceof SodiumKeyPair)) return false;
		return equals((SodiumKeyPair) kp);
	}

	public boolean equals(SodiumKeyPair other) {
		if (!this.seed.equals(other.seed)) return false;
		if (!this.publicKey.equals(other.publicKey)) return false;
		return true;
	}

}
