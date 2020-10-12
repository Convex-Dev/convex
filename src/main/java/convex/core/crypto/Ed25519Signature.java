package convex.core.crypto;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;

import convex.core.data.Address;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

public class Ed25519Signature extends ASignature {

	/**
	 * Length in bytes of an Ed25519 signature
	 */
	public static final int SIGNATURE_LENGTH = 64;
	
	private final byte[] signatureBytes;
	
	private Ed25519Signature(byte[] signature) {
		this.signatureBytes=signature;
	}
	
	public static Ed25519Signature wrap(byte[] signature) {
		if (signature.length!=SIGNATURE_LENGTH) throw new IllegalArgumentException("Bsd signature length for ED25519");
		return new Ed25519Signature(signature);
	}
	
	@Override
	public boolean isCanonical() {
		return true;
	}
	
	
	public static Ed25519Signature read(ByteBuffer bb) throws BadFormatException {
		byte[] sigData=new byte[SIGNATURE_LENGTH];
		bb.get(sigData);
		return wrap(sigData);
	}

	@Override
	public int write(byte[] bs, int pos) {
		bs[pos++]=Tag.SIGNATURE;
		return writeRaw(bs,pos);
	}
	
	@Override
	public int writeRaw(byte[] bs, int pos) {
		System.arraycopy(signatureBytes, 0, bs, pos, SIGNATURE_LENGTH);
		return pos+SIGNATURE_LENGTH;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#signature \""+Utils.toHexString(signatureBytes)+"\"");
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append("{:signature 0x"+Utils.toHexString(signatureBytes)+"}");
	}

	@Override
	public boolean verify(Hash hash, Address address) {
	    PublicKey pk=Ed25519KeyPair.publicKeyFromBytes(address.getBytes());
	    return verify(hash,pk);
	}
	
	public boolean verify(Hash hash, PublicKey publicKey) {
		try {
			Signature verifier = Signature.getInstance("Ed25519");
		    verifier.initVerify(publicKey);
		    verifier.update(hash.getInternalArray(),hash.getOffset(),Hash.LENGTH);
			return verifier.verify(signatureBytes);
		} catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
			throw new Error(e);
		}
	}

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
