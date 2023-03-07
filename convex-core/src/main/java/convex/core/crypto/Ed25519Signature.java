package convex.core.crypto;

import java.nio.ByteBuffer;

import convex.core.data.AArrayBlob;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Immutable data value class representing an Ed25519 digital signature.
 */
public class Ed25519Signature extends ASignature {

	/**
	 * Length in bytes of an Ed25519 signature
	 */
	public static final int SIGNATURE_LENGTH = 64;

	/**
	 * A Signature containing zero bytes (not valid)
	 */
	public static final Ed25519Signature ZERO = wrap(new byte[SIGNATURE_LENGTH]);
	

	private Ed25519Signature(byte[] signature) {
		super(signature);
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
	public Ed25519Signature toCanonical() {
		return this;
	}
	
	@Override public final boolean isCVMValue() {
		// We don't want Signatures in the CVM itself. Should always be checked by Peer.
		return false;
	}
	
	/**
	 * Read a signature from a ByteBuffer. Assumes tag already read.
	 * @param bb ByteBuffer to read from
	 * @return Signature instance
	 * @throws BadFormatException If encoding is invalid
	 */
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
		System.arraycopy(store, 0, bs, pos, SIGNATURE_LENGTH);
		return pos+SIGNATURE_LENGTH;
	}

	@Override
	public AString toCVMString(long limit) {
		if (limit<10) return null;
		return Strings.create(toString());
	}

	//@Override
	//public boolean verify(Hash hash, AccountKey address) {
	//    PublicKey pk=Ed25519KeyPair.publicKeyFromBytes(address.getBytes());
	//    return verify(hash,pk);
	//}
	
	@Override
	public boolean verify(AArrayBlob message, AccountKey publicKey) {
	    return Providers.verify(this,message,publicKey);
	}
	
//	private boolean verify(Hash hash, PublicKey publicKey) {
//		try {
//			Signature verifier = Signature.getInstance("Ed25519");
//		    verifier.initVerify(publicKey);
//		    verifier.update(hash.getInternalArray(),hash.getOffset(),Hash.LENGTH);
//			return verifier.verify(store);
//		} catch (SignatureException | InvalidKeyException e) {	
//			return false;
//		} catch (NoSuchAlgorithmException e) {
//			throw new Error(e);
//		} 
//	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (store.length!=SIGNATURE_LENGTH) throw new InvalidDataException("Bad signature array length?",this);
	}

	@Override
	public int estimatedEncodingSize() {
		return 1+SIGNATURE_LENGTH;
	}

	@Override
	public byte[] getBytes() {
		return store;
	}

	@Override
	public Blob getChunk(long i) {
		return Blob.create(store).getChunk(i);
	}

}
