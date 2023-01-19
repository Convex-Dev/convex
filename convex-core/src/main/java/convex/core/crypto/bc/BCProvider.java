package convex.core.crypto.bc;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

import convex.core.crypto.AProvider;
import convex.core.crypto.ASignature;
import convex.core.data.AArrayBlob;
import convex.core.data.AccountKey;
import convex.core.data.Blob;

/**
 * Convex provider class for Sodium
 */
@SuppressWarnings("serial")
public class BCProvider extends AProvider {
	
	public static final BouncyCastleProvider BC=new BouncyCastleProvider();

	public BCProvider() {
		super("Convex-BC", "1.0", "BouncyCastle crypto integration for Convex");
	}
	
	@Override
	public boolean verify(ASignature signature, AArrayBlob message, AccountKey publicKey) {
		byte[] sigBytes=signature.getBytes();
		boolean verified = Ed25519.verify(sigBytes, 0, publicKey.getInternalArray(), publicKey.getInternalOffset(), message.getBytes(), 0, 0);
		return verified;
	}
	
	@Override
	public BCKeyPair create(Blob seed) {
		return BCKeyPair.create(seed);
	}

}
