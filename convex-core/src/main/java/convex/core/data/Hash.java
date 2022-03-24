package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.Constants;
import convex.core.crypto.Hashing;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
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
	public static final int LENGTH = Constants.HASH_LENGTH;
	
	/**
	 * Type of Hash values
	 */
	public static final AType TYPE = Types.BLOB;

	private Hash(byte[] hashBytes, int offset) {
		super(hashBytes, offset, LENGTH);
	}

	private Hash(byte[] hashBytes) {
		super(hashBytes, 0, LENGTH);
	}

	/*
	 * Hash of some common constant values These are useful to have pre-calculated
	 * for efficiency
	 */
	public static final Hash NULL_HASH = Hashing.sha3(new byte[] { Tag.NULL });
	public static final Hash TRUE_HASH = Hashing.sha3(new byte[] { Tag.TRUE });
	public static final Hash FALSE_HASH = Hashing.sha3(new byte[] { Tag.FALSE });
	public static final Hash EMPTY_HASH = Hashing.sha3(new byte[0]);


	/**
	 * Wraps the specified bytes as a Data object Warning: underlying bytes are used
	 * directly. Use only if no external references to the byte array will be
	 * retained.
	 * 
	 * @param hashBytes Bytes to wrap
	 * @return Hash wrapping the given byte array
	 */
	public static Hash wrap(byte[] hashBytes) {
		return new Hash(hashBytes);
	}
	
    /**
     * Wraps the specified blob data as a Hash, sharing the underlying byte array.
     * @param data Blob data of correct size for a Hash. Must have at least enough bytes for a Hash
     * @return Wrapped data as a Hash
     */
	public static Hash wrap(AArrayBlob data) {
		if (data instanceof Hash) return (Hash)data;
		return wrap(data.getInternalArray(),data.getInternalOffset());
	}

	/**
	 * Wraps the specified bytes as a Data object Warning: underlying bytes are used
	 * directly. Use only if no external references to the byte array will be
	 * retained.
	 * 
	 * @param hashBytes Byte array containing hash value
	 * @param offset Offset into byte array for start of hash value
	 * @return Hash wrapping the given byte array segment
	 */
	public static Hash wrap(byte[] hashBytes, int offset) {
		if ((offset < 0) || (offset + LENGTH > hashBytes.length))
			throw new IllegalArgumentException(Errors.badRange(offset, LENGTH));
		return new Hash(hashBytes, offset);
	}

	@Override
	public boolean equals(ABlob other) {
		if (other==null) return false;
		if (other instanceof Hash) return equals((Hash)other);
		if (other.count()!=LENGTH) return false;
		if (other.getType()!=TYPE) return false;
		return other.equalsBytes(this.store, this.offset);
	}

	/**
	 * Tests if the Hash value is precisely equal to another non-null Hash value.
	 * 
	 * @param other Hash to comapre with
	 * @return true if Hashes are equal, false otherwise.
	 */
	public boolean equals(Hash other) {
		if (other == this) return true;
		return Utils.arrayEquals(other.store, other.offset, this.store, this.offset, LENGTH);
	}
	
	/**
	 * Get the first 32 bits of this Hash. Used for Java hashCodes
	 * @return Int representing the first 32 bits
	 */
	public int firstInt() {
		return Utils.readInt(this.store, this.offset);
	}

	/**
	 * Constructs a Hash object from a hex string
	 * 
	 * @param hexString Hex String
	 * @return Hash with the given hex string value, or null is String is not valid
	 */
	public static Hash fromHex(String hexString) {
		byte [] bs=Utils.hexToBytes(hexString);
		if (bs==null) return null;
		if (bs.length!=LENGTH) return null;
		return wrap(bs);
	}

	public static Hash wrap(AArrayBlob data, int offset, int length) {
		return wrap(data.store, data.offset + offset);
	}
	
	/**
	 * Computes the Hash for any ACell value.
	 * 
	 * May return a cached Hash if available in memory.
	 * 
	 * @param value Any Cell
	 * @return Hash of the encoded data for the given value
	 */
	public static Hash compute(ACell value) {
		if (value == null) return NULL_HASH;
		return value.getHash();
	}

	/**
	 * Reads a Hash from a ByteBuffer Assumes no Tag or count, i.e. just Hash.LENGTH for the
	 * hash is read.
	 * 
	 * @param bb ByteBuffer to read from
	 * @return Hash object read from ByteBuffer
	 */
	public static Hash readRaw(ByteBuffer bb) {
		byte[] bs = new byte[Hash.LENGTH];
		bb.get(bs);
		return Hash.wrap(bs);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.BLOB;
		bs[pos++]=LENGTH;
		return encodeRawData(bs,pos);
	}

	@Override
	public boolean isCanonical() {
		// never canonical, since the canonical version is a Blob
		return false;
	}
	
	@Override
	public Blob toCanonical() {
		return toFlatBlob();
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}

	@Override
	public int estimatedEncodingSize() {
		// tag plus raw data
		return 1 + LENGTH;
	}
	
	@Override
	public long getEncodingLength() {
		// Always a fixed encoding length, tag plus count plus length
		return 2 + LENGTH;
	}

	@Override
	public Blob getChunk(long i) {
		if (i != 0) throw new IndexOutOfBoundsException(Errors.badIndex(i));
		return toFlatBlob();
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
		return true;
	}

	@Override
	public byte getTag() {
		return Tag.BLOB;
	}


}
