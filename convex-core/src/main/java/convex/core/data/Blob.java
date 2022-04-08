package convex.core.data;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import convex.core.data.type.Types;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * General purpose immutable wrapper for byte array data.
 * 
 * Can be serialised directly if 4096 bytes or less, otherwise needs to be
 * structures as a BlobTree.
 * 
 * Encoding format is:
 * - Tag.BLOB tag byte
 * - VLC encoded Blob length in bytes (one or two bytes describing a length in range 0..4096)
 * - Byte data of the given length
 */
public class Blob extends AArrayBlob {
	public static final Blob EMPTY = wrap(Utils.EMPTY_BYTES);
	public static final Blob NULL_ENCODING = Blob.wrap(new byte[] {Tag.NULL});
	
	public static final int CHUNK_LENGTH = 4096;

	private Blob(byte[] bytes, int offset, int length) {
		super(bytes, offset, length);
	}

	/**
	 * Creates a new data object using a copy of the specified byte range
	 * 
	 * @param data Byte array
	 * @param offset Start offset in the byte array
	 * @param length Number of bytes to take from data array
	 * @return The new Data object
	 */
	public static Blob create(byte[] data, int offset, int length) {
		if (length <= 0) {
			if (length == 0) return EMPTY;
			throw new IllegalArgumentException(Errors.negativeLength(length));
		}
		byte[] store = Arrays.copyOfRange(data, offset, offset + length);
		return wrap(store);
	}

	/**
	 * Creates a new data object using a copy of the specified byte array.
	 * 
	 * @param data Byte array
	 * @return Blob with the same byte contents as the given array
	 */
	public static Blob create(byte[] data) {
		return create(data, 0, data.length);
	}

	/**
	 * Wraps the specified bytes as a Data object Warning: underlying bytes are used
	 * directly. Use only if no other references to the byte array are kept which
	 * might be mutated.
	 * 
	 * @param data Byte array
	 * @return Blob wrapping the given data
	 */
	public static Blob wrap(byte[] data) {
		return new Blob(data, 0, data.length);
	}

	/**
	 * Wraps the specified bytes as a Data object Warning: underlying bytes are used
	 * directly. Use only if no other references to the byte array are kept which
	 * might be mutated.
	 * 
	 * @param data Byte array
	 * @param offset Offset into byte array
	 * @param length Length of byte array to wrap
	 * @return Blob wrapping the given byte array segment
	 */
	public static Blob wrap(byte[] data, int offset, int length) {
		if (length < 0) throw new IllegalArgumentException(Errors.negativeLength(length));
		if ((offset < 0) || (offset + length > data.length))
			throw new IndexOutOfBoundsException(Errors.badRange(offset, length));
		if (length==0) return Blob.EMPTY;
		return new Blob(data, offset, length);
	}

	@Override
	public Blob toFlatBlob() {
		return this;
	}

	@Override
	public Blob slice(long start, long end) {
		if (start < 0) return null;
		if (end > this.length) return null;
		long length=end-start;
		if (length < 0) return null;
		if (length == 0) return EMPTY;
		if (length==this.length) return this;
		return Blob.wrap(store, Utils.checkedInt(start + offset), Utils.checkedInt(length));
	}

	@Override
	public boolean equals(ABlob a) {
		if (a==null) return false;
		if (a instanceof Blob) return equals((Blob) a);
		long n=count();
		if (a.count()!=n) return false;
		if (!(a.getType()==Types.BLOB)) return false;
		if (n<=CHUNK_LENGTH) {
			return a.equalsBytes(this.store, this.offset);
		} else {
			return getEncoding().equals(a.getEncoding());
		}
	}

	public boolean equals(Blob b) {
		if (length!=b.length) return false;
		return Arrays.equals(store, offset, offset+length, b.store, b.offset, b.offset+length);
	}

	/**
	 * Equality for array Blob objects
	 * 
	 * Implemented by testing equality of byte data
	 * 
	 * @param other Blob to comapre with
	 * @return true if blobs are equal, false otherwise.
	 */
	public boolean equals(AArrayBlob other) {
		if (other == this) return true;
		if (this.length != other.length) return false;

		// avoid false positives with other Blob types, especially Hash and Address
		if (this.getType() != other.getType()) return false;

		if ((contentHash != null) && (other.contentHash != null) && contentHash.equals(other.contentHash)) return true;
		return Utils.arrayEquals(other.store, other.offset, this.store, this.offset, this.length);
	}

	/**
	 * Constructs a Blob object from a hex string
	 * 
	 * @param hexString Hex String to read
	 * @return Blob with the provided hex value, or null if not a valid blob
	 */
	public static Blob fromHex(String hexString) {
		byte[] bs=Utils.hexToBytes(hexString);
		if (bs==null) return null;
		return wrap(bs);
	}

	/**
	 * Constructs a Blob object from all remaining bytes in a ByteBuffer
	 * 
	 * @param bb ByteBuffer
	 * @return Blob containing the contents read from the ByteBuffer
	 */
	public static Blob fromByteBuffer(ByteBuffer bb) {
		int count = bb.remaining();
		byte[] bs = new byte[count];
		bb.get(bs);
		return Blob.wrap(bs);
	}

	@Override
	public ByteBuffer getByteBuffer() {
		if (offset == 0) {
			return ByteBuffer.wrap(store, offset, length).asReadOnlyBuffer();
		} else {
			return ByteBuffer.wrap(this.getBytes()).asReadOnlyBuffer();
		}
	}

	/**
	 * Fast read of a Blob from its representation insider another Blob object,
	 * 
	 * Main benefit is to avoid reconstructing via ByteBuffer allocation, enabling
	 * retention of source Blob object as encoded data.
	 * 
	 * @param source Source Blob object.
	 * @param len Length in bytes to take from the source Blob
	 * @return Blob read from the source
	 * @throws BadFormatException If encoding is invalid
	 */
	public static AArrayBlob read(Blob source, long len) throws BadFormatException {
		// compute data length, excluding tag and encoded length
		int headerLength = (1 + Format.getVLCLength(len));
		long rLen = source.count() - headerLength;
		if (len != rLen) {
			throw new BadFormatException("Invalid length for Blob, length field " + len + " but actual length " + rLen);
		}

		return source.slice(headerLength, headerLength+len);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		if (length > CHUNK_LENGTH) {
			return getCanonical().encode(bs, pos);
		} else {
			// we have a Blob of canonical size
			bs[pos++]=Tag.BLOB;
			pos=encodeRaw(bs,pos);
			return pos;
		}
	}
	
	@Override
	public int estimatedEncodingSize() {
		// space for tag, generous VLC length, plus raw data
		return 1 + Format.MAX_VLC_LONG_LENGTH + length;
	}
	
	/**
	 * Maximum encoding size for a regular Blob
	 */
	public static final int MAX_ENCODING_LENGTH=1+Format.getVLCLength(CHUNK_LENGTH)+CHUNK_LENGTH;

	@Override
	public boolean isCanonical() {
		return length <= Blob.CHUNK_LENGTH;
	}

	/**
	 * Creates a Blob of random bytes of the given length
	 * 
	 * @param random Any Random generator instance
	 * @param length Length of Blob to generate in bytes
	 * @return Blob with the specified number of random bytes
	 */
	public static Blob createRandom(Random random, long length) {
		byte[] randBytes = new byte[Utils.checkedInt(length)];
		random.nextBytes(randBytes);
		return wrap(randBytes);
	}

	@Override
	public Blob getChunk(long i) {
		if ((i == 0) && (length <= CHUNK_LENGTH)) return this;
		long start = i * CHUNK_LENGTH;
		long take=Math.min(CHUNK_LENGTH, length - start);
		return slice(start, start+take);
	}

	public void attachContentHash(Hash hash) {
		if (contentHash == null) contentHash = hash;
	}

	@Override
	public byte getTag() {
		return Tag.BLOB;
	}

	@Override
	public ABlob toCanonical() {
		if (isCanonical()) return this;
		return Blobs.toCanonical(this);
	}
	




}