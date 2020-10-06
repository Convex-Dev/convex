package convex.core.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.bouncycastle.jcajce.provider.digest.Keccak;

import convex.core.data.AArrayBlob;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Tag;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Class used to represent an immutable 32-byte Hash value.
 * 
 * The Hash algorithm used may depend on context.
 * 
 * This is intended to help with type safety vs. regular Blob objects and as a
 * useful type as a key in relevant data structures.
 * 
 * "Companies spend millions of dollars on firewalls, encryption and secure
 * access devices, and it's money wasted, because none of these measures address
 * the weakest link in the security chain." - Kevin Mitnick
 *
 */
public class Hash extends AArrayBlob {
	/**
	 * Standard length of a Hash in bytes
	 */
	public static final int LENGTH = 32;

	/**
	 * Threadlocal store for MessageDigets instances. TODO: figure out if this is
	 * useful for performance. Probably not since digest initialisation is the
	 * bottleneck anyway?
	 */
	@SuppressWarnings("unused")
	private static final ThreadLocal<MessageDigest> KECCAK_DIGEST = new ThreadLocal<MessageDigest>() {
		@Override
		protected MessageDigest initialValue() {
			return new Keccak.Digest256();
		}
	};



	private Hash(byte[] hashBytes) {
		super(hashBytes, 0, hashBytes.length);
	}

	private Hash(byte[] hashBytes, int offset, int length) {
		super(hashBytes, offset, length);
	}

	/**
	 * Private store for thread-local MessageDigent objects. Avoids cost of
	 * recreating these every time they are needed.
	 */
	private static final ThreadLocal<MessageDigest> sha256Store;

	/**
	 * Private store for thread-local MessageDigent objects. Avoids cost of
	 * recreating these every time they are needed.
	 */
	private static final ThreadLocal<MessageDigest> sha3Store;

	
	static {
		sha256Store = ThreadLocal.withInitial(() -> {
			try {
				return MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				throw new Error("SHA-256 algorithm not available", e);
			}
		});
		
		sha3Store = ThreadLocal.withInitial(() -> {
			try {
				return MessageDigest.getInstance("SHA3-256");
			} catch (NoSuchAlgorithmException e) {
				throw new Error("SHA3-256 algorithm not available", e);
			}
		});
	}
	
	/*
	 * Hash of some common constant values These are useful to have pre-calculated
	 * for efficiency
	 */
	public static final Hash NULL_HASH = sha3(new byte[] { Tag.NULL });
	public static final Hash TRUE_HASH = sha3(new byte[] { Tag.TRUE });
	public static final Hash FALSE_HASH = sha3(new byte[] { Tag.FALSE });
	public static final Hash EMPTY_HASH = sha3(new byte[0]);


	/**
	 * Gets a thread-local instance of a SHA256 MessageDigest
	 * 
	 * @return MessageDigest instance
	 */
	public static MessageDigest getSHA256Digest() {
		return sha256Store.get();
	}
	
	/**
	 * Gets a thread-local instance of a SHA3-256 MessageDigest
	 * 
	 * @return MessageDigest instance
	 */
	public static MessageDigest getSHA3Digest() {
		return sha3Store.get();
	}

	/**
	 * Computes the SHA3-256 hash of byte data
	 * 
	 * @param data
	 * @return SHA3-256 Hash value
	 */
	public static Hash sha256(byte[] data) {
		MessageDigest md = getSHA256Digest();
		byte[] hash = md.digest(data);
		return Hash.wrap(hash);
	}

	/**
	 * Computes the SHA-256 hash of a string
	 * 
	 * @return Hash of UTF-8 encoded string
	 */
	public static Hash sha3(String message) {
		return sha3(message.getBytes(StandardCharsets.UTF_8));
	}
	
	/**
	 * Computes the SHA3-256 hash of byte data
	 * 
	 * @param data
	 * @return SHA3-256 Hash value
	 */
	public static Hash sha3(byte[] data) {
		MessageDigest md = getSHA3Digest();
		byte[] hash = md.digest(data);
		return Hash.wrap(hash);
	}

	/**
	 * Computes the SHA-256 hash of a string
	 * 
	 * @return Hash of UTF-8 encoded string
	 */
	public static Hash sha256(String message) {
		return sha256(message.getBytes(StandardCharsets.UTF_8));
	}
	
	/**
	 * Gets the Convex default MessageDigest. 
	 * 
	 * Guaranteed thread safe, will be either a new or ThreadLocal instance.
	 * 
	 * @return MessageDigest
	 */
	public static MessageDigest getDigest() {
		return getSHA3Digest();
	}

	/**
	 * Gets a MessageDigest for Keccak256. 
	 * 
	 * Guaranteed thread safe, will be either a new or ThreadLocal instance.
	 * 
	 * @return MessageDigest
	 */
	public static MessageDigest getKeccak256Digest() {
		// MessageDigest md= KECCAK_DIGEST.get();
		// md.reset();
		MessageDigest md = new Keccak.Digest256();
		return md;
	}

	/**
	 * Computes the keccak256 hash of a subset of byte data in an array
	 * 
	 * @param data
	 * @param offset
	 * @param length
	 * @return Hash of byte data
	 */
	public static Hash keccak256(byte[] data, int offset, int length) {
		MessageDigest kecc = getKeccak256Digest();
		kecc.update(data, offset, length);
		return wrap(kecc.digest());
	}

	/**
	 * Computes the keccak256 hash of all byte data in an array
	 * 
	 * @param data
	 * @return Hash of byte data
	 */
	public static Hash keccak256(byte[] data) {
		return keccak256(data, 0, data.length);
	}

	/**
	 * Compute the keccak256 hash of the UTF8 encoding of a string
	 * 
	 * @param string
	 * @return Hash of UTF-8 encoded string
	 */
	public static Hash keccak256(String string) {
		return keccak256(string.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Wraps the specified bytes as a Data object Warning: underlying bytes are used
	 * directly. Use only if no external references to the byte array will be
	 * retained.
	 * 
	 * @param hashBytes
	 * @return Hash wrapping the given byte array
	 */
	public static Hash wrap(byte[] hashBytes) {
		return new Hash(hashBytes);
	}
	
    /**
     * Wraps the specified blob data as a Hash, sharing the underlying byte array.
     * @param data Blob data of correct size for a Hash
     * @return
     */
	public static Hash wrap(AArrayBlob data) {
		if (data instanceof Hash) return (Hash)data;
		return wrap(data.getInternalArray(),data.getOffset(),Utils.toInt(data.length()));
	}

	/**
	 * Wraps the specified bytes as a Data object Warning: underlying bytes are used
	 * directly. Use only if no external references to the byte array will be
	 * retained.
	 * 
	 * @param hashBytes
	 * @return Hash wrapping the given byte array segment
	 */
	public static Hash wrap(byte[] hashBytes, int offset, int length) {
		if ((offset < 0) || (offset + length > hashBytes.length))
			throw new IllegalArgumentException(Errors.badRange(offset, length));
		return new Hash(hashBytes, offset, length);
	}

	/**
	 * We use the first bytes as the hashcode for a Hash
	 */
	@Override
	public int hashCode() {
		return Utils.readInt(store, offset);
	}

	@Override
	public boolean equals(ABlob other) {
		if (!(other instanceof Hash)) return false;
		return equals((Hash) other);
	}

	/**
	 * Tests if the Hash value is precisely equal to another non-null Hash value.
	 * 
	 * @param other
	 * @return true if Hashes are equal, false otherwise.
	 */
	public boolean equals(Hash other) {
		if (other == this) return true;
		assert (this.length == other.length);
		return Utils.arrayEquals(other.store, other.offset, this.store, this.offset, this.length);
	}

	/**
	 * Constructs a Hash object from a hex string
	 * 
	 * @param hexString
	 * @return Hash with the given hex string value
	 */
	public static Hash fromHex(String hexString) {
		return wrap(Utils.hexToBytes(hexString));
	}

	public static Hash wrap(AArrayBlob data, int offset, int length) {
		return data.extractHash(offset, length);
	}

	/**
	 * Computes the Hash for any value.
	 * 
	 * May return a cached Hash if available in memory
	 * 
	 * @param value
	 * @return Hash of the encoded data for the given value
	 */
	public static Hash compute(Object value) {
		if (value == null) return NULL_HASH;
		if (value instanceof ACell) return ((ACell) value).getHash();

		if (value instanceof Boolean) {
			return (Boolean) value ? TRUE_HASH : FALSE_HASH;
		}

		AArrayBlob d = Format.encodedBlob(value);
		// SECURITY: make sure we use content hash, and not the d.getHash() (which is the
		// hash of the serialisation of the serialisation of the object!)
		Hash h = d.getContentHash();
		return h;
	}

	/**
	 * Reads a Hash from a ByteBuffer Assumes no Tag, i.e. just Hash.LENGTH for the
	 * hash is read.
	 * 
	 * @param bb
	 * @return Hash object read from ByteBuffer
	 */
	public static Hash read(ByteBuffer bb) {
		byte[] bs = new byte[Hash.LENGTH];
		bb.get(bs);
		return Hash.wrap(bs);
	}

	@Override
	public ByteBuffer write(ByteBuffer bb) {
		bb = bb.put(Tag.HASH);
		return writeRaw(bb);
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#hash 0x");
		sb.append(toHexString());
	}

	@Override
	public boolean isCanonical() {
		// always canonical, since class invariants are maintained
		return true;
	}

	@Override
	public int estimatedEncodingSize() {
		// tag plus raw data
		return 1 + LENGTH;
	}

	@Override
	public Blob getChunk(long i) {
		if (i != 0) throw new IndexOutOfBoundsException(Errors.badIndex(i));
		return toBlob();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (length != LENGTH) throw new InvalidDataException("Address length must be 32 bytes = 256 bits", this);
	}

	@Override
	public boolean isEmbedded() {
		// Hashes are always small enough to embed
		return true;
	}

	@Override
	public boolean isRegularBlob() {
		return false;
	}

}