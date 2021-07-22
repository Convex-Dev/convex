package convex.core.crypto;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;

/**
 * Class representing a cryptographic signature
 */
public abstract class ASignature extends ACell {

	/**
	 * Checks if the signature is valid for a given message hash
	 * @param hash Hash of value to verify
	 * @param publicKey Public key of signer
	 * @return True if signature is valid, false otherwise
	 */
	public abstract boolean verify(Hash hash, AccountKey publicKey);
	
	/**
	 * Reads a Signature from the given ByteBuffer. Assumes tag byte already read.
	 * 
	 * Uses Ed25519
	 * 
	 * @param bb ByteBuffer to read from
	 * @return Signature instance
	 * @throws BadFormatException If encoding is invalid
	 */
	public static ASignature read(ByteBuffer bb) throws BadFormatException {
		return Ed25519Signature.read(bb);
	}
	
	/**
	 * Gets the content of this Signature as a hex string
	 * @return Hex String representation of Signature
	 */
	public abstract String toHexString();
	
	/**
	 * Construct a Signature from a hex string
	 * 
	 * Uses Ed25519 
	 * 
	 * @param hex Hex String to read from
	 * @return Signature instance
	 */
	public static ASignature fromHex(String hex) {
		byte[] bs=Utils.hexToBytes(hex);
		return Ed25519Signature.wrap(bs);
	}
	
	@Override
	public boolean isEmbedded() {
		return true;
	}
	
	@Override
	public byte getTag() {
		return Tag.SIGNATURE;
	}

}
