package convex.core.crypto;

import java.nio.ByteBuffer;

import convex.core.data.AArrayBlob;
import convex.core.data.ABlob;
import convex.core.data.AccountKey;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;

/**
 * Class representing a cryptographic signature
 */
public abstract class ASignature extends AArrayBlob {

	protected ASignature(byte[] signature,int pos) {
		super(signature, pos, Ed25519Signature.SIGNATURE_LENGTH);
	} 

	/**
	 * Checks if the signature is valid for a given message hash
	 * @param message Message to verify
	 * @param publicKey Public key of signer
	 * @return True if signature is valid, false otherwise
	 */
	public abstract boolean verify(AArrayBlob message, AccountKey publicKey);
	
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
	
	/**
	 * Construct a Signature from a Blob
	 * 
	 * Uses Ed25519 
	 * 
	 * @param sigData Blob of data representing raw signature
	 * @return Signature instance
	 */
	public static ASignature fromBlob(ABlob sigData) {
		byte[] bs=sigData.getBytes();
		return Ed25519Signature.wrap(bs);
	}
	
	@Override
	public boolean isEmbedded() {
		return true;
	}
	
	@Override
	public boolean equals(ABlob b) {
		return b.equalsBytes(this);
	}


}
