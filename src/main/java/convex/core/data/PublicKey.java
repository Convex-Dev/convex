package convex.core.data;

import java.math.BigInteger;
import java.nio.ByteBuffer;

import convex.core.crypto.Hash;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Immutable class representing an Ed25519 Public Key.
 * 
 * <p>
 * Using Ed25519:
 * </p>
 * <li>Addresses are the Public Key (32 bytes)</li>
 * 
 */
public class PublicKey extends AArrayBlob {
	public static final int LENGTH = 32;

	public static final int LENGTH_BITS = LENGTH * 8;

	public static final PublicKey ZERO = PublicKey.dummy("0");

	private PublicKey(byte[] data, int offset, int length) {
		super(data, offset, length);
		if (length != LENGTH) throw new IllegalArgumentException("Address length must be " + LENGTH_BITS + " bits");
	}

	/**
	 * Wraps the specified bytes as an Address object Warning: underlying bytes are
	 * used directly. Use only if no external references to the byte array will be
	 * retained.
	 * 
	 * @param data
	 * @return An Address wrapping the given bytes
	 */
	public static PublicKey wrap(byte[] data) {
		return new PublicKey(data, 0, data.length);
	}

	/**
	 * Wraps the specified bytes as an Address object Warning: underlying bytes are
	 * used directly. Use only if no external references to the byte array will be
	 * retained.
	 * 
	 * @param data   Data array containing address bytes.
	 * @param offset Offset into byte array
	 * @return An Address wrapping the given bytes
	 */
	public static PublicKey wrap(byte[] data, int offset) {
		return new PublicKey(data, offset, LENGTH);
	}

	private static PublicKey wrap(AArrayBlob source) {
		return new PublicKey(source.store, source.offset, source.length);
	}

	/**
	 * Creates a "Dummy" Address that is not a valid public key, and therefore
	 * cannot have valid signed transactions.
	 * 
	 * To do this, a short hex nonce is repeated to fill the entire address length. This
	 * construction makes it possible to examine an Address and assess whether it is (plausibly) 
	 * a dummy address.
	 * 
	 * @param string Hex string to repeat to produce a visible dummy address
	 * @return An Address that cannot be used to sign transactions.
	 */
	public static PublicKey dummy(String nonce) {
		int n = nonce.length();
		if (n == 0) throw new Error("Empty nonce");
		if (n >= LENGTH / 2) throw new Error("Nonce too long for dummy address");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < LENGTH * 2; i += n) {
			sb.append(nonce);
		}
		return PublicKey.fromHex(sb.substring(0, LENGTH * 2));
	}

	@Override
	public int hashCode() {
		// note: We use the first bytes as the hashcode for an Address.
		// effectively randomly distributed for public keys
		// avoids collisions with different nonces for dummy addresses
		return Utils.readInt(store, offset);
	}

	@Override
	public boolean equals(ABlob o) {
		if (!(o instanceof PublicKey)) return false;
		return equals((PublicKey) o);
	}

	public boolean equals(PublicKey o) {
		if (o == this) return true;
		return Utils.arrayEquals(o.store, o.offset, this.store, this.offset, LENGTH);
	}

	/**
	 * Constructs an Address object from a hex string
	 * 
	 * @param hexString
	 * @return An Address constructed from the hex string, or null if not a valid
	 *         hex string
	 */
	public static PublicKey fromHex(String hexString) {
		PublicKey result = fromHexOrNull(hexString);
		if (result == null) throw new Error("Invalid Address hex String [" + hexString + "]");
		return result;
	}

	/**
	 * Constructs an Address object from a hex string
	 * 
	 * @param hexString
	 * @return An Address constructed from the hex string, or null if not a valid
	 *         hex string
	 */
	public static PublicKey fromHexOrNull(String hexString) {
		byte[] bs = Utils.hexToBytes(hexString, LENGTH * 2);
		if (bs == null) return null; // invalid string
		if (bs.length != LENGTH) return null; // wrong length
		return wrap(bs);
	}

	/**
	 * Constructs an Address object from a checksummed hex string.
	 * 
	 * Throws an exception if checksum is not valid
	 * 
	 * @param hexString
	 * @return An Address constructed from the hex string
	 */
	public static PublicKey fromChecksumHex(String hexString) {
		byte[] bs = Utils.hexToBytes(hexString, LENGTH * 2);
		PublicKey a = PublicKey.wrap(bs);
		Hash h = a.getContentHash();
		for (int i = 0; i < LENGTH * 2; i++) {
			int dh = h.getHexDigit(i);
			char c = hexString.charAt(i);
			if (Character.isDigit(c)) continue;
			boolean check = (c >= 'a') ^ (dh >= 8); // note 'a' is higher than 'A'
			if (!check)
				throw new IllegalArgumentException("Bad checksum at position " + i + " in address " + hexString);
		}
		return a;
	}

	/**
	 * Converts this Address to a checksummed hex string.
	 * 
	 * @return A String containing the checksummed hex representation of this
	 *         Address
	 */
	public String toChecksumHex() {
		StringBuilder sb = new StringBuilder(64);
		Hash h = this.getContentHash();
		for (int i = 0; i < LENGTH * 2; i++) {
			int dh = h.getHexDigit(i);
			int da = this.getHexDigit(i);
			if (da < 10) {
				sb.append((char) ('0' + da));
			} else {
				boolean up = (dh >= 8);
				sb.append((char) ((up ? 'A' : 'a') + da - 10));
			}
		}
		return sb.toString();
	}

	/**
	 * Computes an Address from the Keccak256 hash of a 512 bit public key.
	 * 
	 * Method as used in Bitcoin / Ethereum. Not relevant in Ed25519 mode.
	 * 
	 * @param publicKey
	 * @return The Address representing the given public key.
	 */
	public static PublicKey fromPublicKey(byte[] publicKey) {
		if (publicKey.length != 64)
			throw new IllegalArgumentException("Address creation requires a 512 bit public key");
		Hash hash = Hash.keccak256(publicKey);
		return fromHash(hash); // Take last bytes of 256bit hash, up to the required Address length
	}

	/**
	 * Create a synthetic Address from a Hash value.
	 * 
	 * SECURITY: Will be a usable user Address if and only if the Hash is of the
	 * ECDSA 64 byte public key.
	 * 
	 * @param hash The hash of the ECDSA public key.
	 * @return Address generated from hash
	 */
	public static PublicKey fromHash(Hash hash) {
		return wrap(hash.slice(Hash.LENGTH - LENGTH, LENGTH)); // take last bytes of hash, in case Address is shorter
	}

	/**
	 * Computes an Address from a BigInteger public key
	 * 
	 * @param pubKey The public key from which to compute the Address
	 * @return The Address representing the given public key.
	 */
	public static PublicKey fromPublicKey(BigInteger pubKey) {
		byte[] publicKey = new byte[64];
		Utils.writeUInt(pubKey, publicKey, 0, 64);
		return fromPublicKey(publicKey);
	}

	public static PublicKey readRaw(ByteBuffer data) {
		byte[] buff = new byte[LENGTH];
		data.get(buff);
		return PublicKey.wrap(buff);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.ADDRESS;
		return encodeRaw(bs,pos);
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#addr 0x");
		sb.append(toHexString());
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append("0x");
		sb.append(toChecksumHex());
	}

	@Override
	public boolean isCanonical() {
		// always canonical, since class invariants are maintained
		return true;
	}

	@Override
	public int estimatedEncodingSize() {
		// tag plus LENGTH bytes
		return 1 + LENGTH;
	}

	@Override
	public Blob getChunk(long i) {
		if (i != 0) throw new IndexOutOfBoundsException(Errors.badIndex(i));
		return toBlob();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (length != LENGTH)
			throw new InvalidDataException("Address length must be " + LENGTH + "  bytes = " + LENGTH_BITS + " bits",
					this);
	}

	@Override
	public boolean isEmbedded() {
		return true;
	}

	@Override
	public boolean isRegularBlob() {
		return false;
	}

}
