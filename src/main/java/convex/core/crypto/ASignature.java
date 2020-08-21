package convex.core.crypto;

import java.nio.ByteBuffer;

import convex.core.Constants;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.TODOException;
import convex.core.util.Utils;

public abstract class ASignature extends ACell {

	/**
	 * Checks if the signature is valid for a given message hash
	 * @param hash
	 * @param address
	 * @return True if signature is vaid, false otherwise
	 */
	public abstract boolean verify(Hash hash, Address address);
	
	/**
	 * Reads a Signature from the given ByteBuffer. Assumes tag byte already read.
	 * 
	 * Uses Ed25519 or ECDSA as configured.
	 * 
	 * @param bb
	 * @throws BadFormatException
	 */
	public static ASignature read(ByteBuffer bb) throws BadFormatException {
		if (Constants.USE_ED25519) {
			return Ed25519Signature.read(bb);
		} else {
			return ECDSASignature.read(bb);
		}
	}
	
	/**
	 * Reads a Signature from the given ByteBuffer. Assumes tag byte already read.
	 * 
	 * Uses Ed25519 or ECDSA as configured.
	 * 
	 * @param bb
	 * @throws BadFormatException
	 */
	public static ASignature fromHex(String hex) throws BadFormatException {
		byte[] bs=Utils.hexToBytes(hex);
		if (Constants.USE_ED25519) {
			return Ed25519Signature.wrap(bs);
		} else {
			throw new TODOException();
		}
	}
	
	@Override
	protected boolean isEmbedded() {
		// Signatures are too long to embded
		return false;
	}

}
