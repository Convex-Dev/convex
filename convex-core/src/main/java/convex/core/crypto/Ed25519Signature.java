package convex.core.crypto;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Immutable dtata value class representing an Ed25519 digital signature.
 */
public class Ed25519Signature extends ASignature {

	/**
	 * Length in bytes of an Ed25519 signature
	 */
	public static final int SIGNATURE_LENGTH = 64;

	/**
	 * A Signature containing zerod bytes (not valid)
	 */
	public static final ASignature ZERO = wrap(new byte[SIGNATURE_LENGTH]);
	
	private final byte[] signatureBytes;
	
	private Ed25519Signature(byte[] signature) {
		this.signatureBytes=signature;
	}
	
	/**
	 * Creates a Signature instance with specific bytes
	 * @param signature Bytes for signature
	 * @return Signature instance
	 */
	public static Ed25519Signature wrap(byte[] signature) {
		if (signature.length!=SIGNATURE_LENGTH) throw new IllegalArgumentException("Bsd signature length for ED25519");
		return new Ed25519Signature(signature);
	}
	
	@Override
	public boolean isCanonical() {
		return true;
	}
	
	@Override
	public ACell toCanonical() {
		return this;
	}
	
	@Override public final boolean isCVMValue() {
		return false;
	}
	
	
	public static Ed25519Signature read(ByteBuffer bb) throws BadFormatException {
		byte[] sigData=new byte[SIGNATURE_LENGTH];
		bb.get(sigData);
		return wrap(sigData);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.SIGNATURE;
		return encodeRaw(bs,pos);
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		System.arraycopy(signatureBytes, 0, bs, pos, SIGNATURE_LENGTH);
		return pos+SIGNATURE_LENGTH;
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append("{:signature 0x"+Utils.toHexString(signatureBytes)+"}");
	}

	//@Override
	//public boolean verify(Hash hash, AccountKey address) {
	//    PublicKey pk=Ed25519KeyPair.publicKeyFromBytes(address.getBytes());
	//    return verify(hash,pk);
	//}
	
	@Override
	public boolean verify(Hash hash, AccountKey address) {
	    boolean verified = Providers.SODIUM_SIGN.cryptoSignVerifyDetached(signatureBytes, hash.getBytes(), 32, address.getBytes());
	    return verified;
	}
	
//	private boolean verify(Hash hash, PublicKey publicKey) {
//		try {
//			Signature verifier = Signature.getInstance("Ed25519");
//		    verifier.initVerify(publicKey);
//		    verifier.update(hash.getInternalArray(),hash.getOffset(),Hash.LENGTH);
//			return verifier.verify(signatureBytes);
//		} catch (SignatureException | InvalidKeyException e) {	
//			return false;
//		} catch (NoSuchAlgorithmException e) {
//			throw new Error(e);
//		} 
//	}

	@Override
	public void validateCell() throws InvalidDataException {
		// TODO Auto-generated method stub

	}

	@Override
	public int estimatedEncodingSize() {
		return 1+SIGNATURE_LENGTH;
	}
	
	@Override
	public int getRefCount() {
		return 0;
	}

	@Override
	public String toHexString() {
		return Utils.toHexString(signatureBytes);
	}



}
