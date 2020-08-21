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

	private static final int SIGNATURE_LENGTH = 64;
	
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
	public ByteBuffer write(ByteBuffer bb) {
		bb = bb.put(Tag.SIGNATURE);
		return writeRaw(bb);
	}
	
	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		bb.put(signatureBytes);
		return bb;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#signature \""+Utils.toHexString(signatureBytes)+"\"");
	}

	@Override
	public boolean verify(Hash hash, Address address) {
	    PublicKey pk=Ed25519KeyPair.publicKeyFromBytes(address.getBytes());
	    return verify(hash,pk);
	}
	
	public boolean verify(Hash hash, PublicKey publicKey) {
		try {
			Signature verifier;
			verifier = Signature.getInstance("EdDSA");
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
		// TODO Auto-generated method stub
		return 128;
	}
	
	@Override
	public int getRefCount() {
		return 0;
	}


}
