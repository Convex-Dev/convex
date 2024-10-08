package convex.core.data;

import convex.core.Constants;
import convex.core.crypto.Hashing;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.ErrorMessages;
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
	public static final int HEX_LENGTH = LENGTH*2;
	
	private Hash(byte[] hashBytes, int offset) {
		super(hashBytes, offset, LENGTH);
		this.memorySize=0;
	}

	private Hash(byte[] hashBytes) {
		super(hashBytes, 0, LENGTH);
		this.memorySize=Format.FULL_EMBEDDED_MEMORY_SIZE;
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
	 * @param hashBytes Bytes to wrap (must be correct length)
	 * @return Hash wrapping the given byte array
	 */
	public static Hash wrap(byte[] hashBytes) {
		return new Hash(hashBytes);
	}
	
	/**
	 * Wraps the Blob as a Hash if possible
	 * @param a Any ABlob instance
	 * @return Hash instance, or null if argument is null or wrong length
	 */
	public static Hash wrap(ABlob a) {
		if (a==null) return null;
		if (a.count()!=LENGTH) return null;
		if (a instanceof AArrayBlob) return wrap((AArrayBlob)a);
		return wrap(a.getBytes());
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
     * Wraps the specified blob data as a Hash, sharing the underlying byte array.
     * @param data Blob data of correct size for a Hash. Must have at least enough bytes for a Hash
	 * @param pos Position ib Blob to read from
     * @return Wrapped data as a Hash, or null if insufficent bytes in source Blob
     */
	public static Hash wrap(AArrayBlob data, int pos) {
		if ((pos==0) &&(data instanceof Hash)) return (Hash)data;
		if (pos+LENGTH>data.count()) return null;
		return wrap(data.getInternalArray(),Utils.checkedInt(data.getInternalOffset()+pos));
	}

	/**
	 * Wraps the specified bytes as a Data object Warning: underlying bytes are used
	 * directly. Use only if no external references to the byte array will be
	 * retained, and the byte array is effectively immutable.
	 * 
	 * @param hashBytes Byte array containing hash value
	 * @param offset Offset into byte array for start of hash value
	 * @return Hash wrapping the given byte array segment
	 */
	public static Hash wrap(byte[] hashBytes, int offset) {
		if ((offset < 0) || (offset + LENGTH > hashBytes.length))
			throw new IllegalArgumentException(ErrorMessages.badRange(offset, offset+LENGTH));
		Hash h= new Hash(hashBytes, offset);
		if ((offset>=2)&&(hashBytes[offset-1]==LENGTH)&&(hashBytes[offset-2]==Tag.BLOB)) {
			h.attachEncoding(Blob.wrap(hashBytes, offset-2, LENGTH+2));
		}
		return h;
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
	
	/**
	 * Best effort attempt to parse a Hash. Must parse as a blob of correct length
	 * @param o Object expected to contain a Hash value
	 * @return Hash value, or null if not parseable
	 */
	public static Hash parse(Object o) {
		return wrap(Blobs.parse(o));
	}
	
	/**
	 * Best effort attempt to parse a Hash. Must parse as a blob of correct length.
	 * Leading "0x" optional.
	 * @param s String expected to contain a Hash value
	 * @return Hash value, or null if not parseable
	 */
	public static Hash parse(String s) {
		return wrap(Blobs.parse(s));
	}
	
	/**
	 * Computes the Hash for any ACell value.
	 * 
	 * May return a cached Hash if available in memory.
	 * 
	 * @param value Any Cell
	 * @return Hash of the encoded data for the given value
	 */
	public static Hash get(ACell value) {
		if (value == null) return NULL_HASH;
		return value.getHash();
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

	@Override
	public int estimatedEncodingSize() {
		// tag plus length plis raw data
		return 2 + LENGTH;
	}
	
	@Override
	public int getEncodingLength() {
		// Always a fixed encoding length, tag plus count plus length
		return 2 + LENGTH;
	}

	@Override
	public Blob getChunk(long i) {
		if (i != 0) throw new IndexOutOfBoundsException(ErrorMessages.badIndex(i));
		return toFlatBlob();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (count != LENGTH) throw new InvalidDataException("Hash length must be 32 bytes = 256 bits", this);
	}

	@Override
	public boolean isEmbedded() {
		// Hashes are always small enough to embed
		return true;
	}
	
	/**
	 * Optimised compareTo for Hashes. Needed for MapLeaf, SetLeaf etc.
	 * @param b Other Hash to compare with
	 * @return Negative if this is "smaller", 0 if this "equals" b, positive if this is "larger"
	 */
	public final int compareTo(Hash b) {
		if (this == b) return 0;	
		// Check common bytes first
		int c = Utils.compareByteArrays(this.store, this.offset, b.store, b.offset, LENGTH);
		return c;
	}
	
	/**
	 * Tests if the Hash value is precisely equal to another non-null Hash value.
	 * 
	 * @param other Hash to compare with
	 * @return true if Hashes are equal, false otherwise.
	 */
	public boolean equals(Hash other) {
		if (other == this) return true;
		return Utils.arrayEquals(other.store, other.offset, this.store, this.offset, LENGTH);
	}
}
