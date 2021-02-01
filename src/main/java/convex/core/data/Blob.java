package convex.core.data;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import convex.core.crypto.Hash;
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
	
	/**
	 * Maximim encoding size for a Blob
	 */
	public static final int MAX_ENCODING_LENGTH = 1+2+CHUNK_LENGTH;

	private Blob(byte[] bytes, int offset, int length) {
		super(bytes, offset, length);
	}

	/**
	 * Creates a new data object using a copy of the specified byte range
	 * 
	 * @param data
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
	 * @param data
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
	 * @param data
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
	 * @param data
	 * @return Blob wrapping the given byte array segment
	 */
	public static Blob wrap(byte[] data, int offset, int length) {
		if (length < 0) throw new IllegalArgumentException(Errors.negativeLength(length));
		if ((offset < 0) || (offset + length > data.length))
			throw new IndexOutOfBoundsException(Errors.badRange(offset, length));
		return new Blob(data, offset, length);
	}

	@Override
	public Blob toBlob() {
		return this;
	}

	@Override
	public Blob slice(long start, long length) {
		if (start < 0) throw new IllegalArgumentException("Start out of bounds: " + start);
		if ((start + length) > this.length)
			throw new IllegalArgumentException("End out of bounds: " + (start + length));
		if (length < 0) throw new IllegalArgumentException("Negative length of slice: " + length);
		if (length == 0) return EMPTY;
		return Blob.wrap(store, Utils.checkedInt(start + offset), Utils.checkedInt(length));
	}

	@Override
	public boolean equals(Object a) {
		if (a instanceof ABlob) return equals((ABlob) a);
		return false;
	}

	@Override
	public boolean equals(ABlob b) {
		if (b instanceof AArrayBlob) return equals((AArrayBlob) b);
		if (b instanceof LongBlob) {
			if (length != 8) return false;
			return ((LongBlob) b).longValue() == Utils.readLong(store, offset);
		}
		return false;
	}

	/**
	 * Equality for array Blob objects
	 * 
	 * Implemented by testing equality of byte data
	 * 
	 * @param other
	 * @return true if blobs are equal, false otherwise.
	 */
	public boolean equals(AArrayBlob other) {
		if (other == this) return true;
		if (this.length != other.length) return false;

		// avoid false positives with other Blob types, especially Hash and Address
		if (this.getClass() != other.getClass()) return false;

		if ((contentHash != null) && (other.contentHash != null) && contentHash.equals(other.contentHash)) return true;
		return Utils.arrayEquals(other.store, other.offset, this.store, this.offset, this.length);
	}

	/**
	 * Constructs a Blob object from a hex string
	 * 
	 * @param hexString
	 * @return Blob with the provided hex value
	 */
	public static Blob fromHex(String hexString) {
		byte[] bs=Utils.hexToBytes(hexString);
		if (bs==null) throw new IllegalArgumentException("Invalid hex string for blob ["+hexString+"]");
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
	 * @return Blob read from the source
	 * @throws BadFormatException
	 */
	public static AArrayBlob read(Blob source, long len) throws BadFormatException {
		// compute data length, excluding tag and encoded length
		int headerLength = (1 + Format.getVLCLength(len));
		long rLen = source.length() - headerLength;
		if (len != rLen) {
			throw new BadFormatException("Invalid length for Blob, length field " + len + " but actual length " + rLen);
		}

		return source.slice(headerLength, len);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		if (length > CHUNK_LENGTH) {
			return BlobTree.create(this).encode(bs,pos);
		} else {
			// we have a Blob of canonical size
			bs[pos++]=Tag.BLOB;
			pos=Format.writeVLCLong(bs, pos, length);
			pos=encodeRaw(bs,pos);
			return pos;
		}
	}

	@Override
	public boolean isCanonical() {
		return length <= Blob.CHUNK_LENGTH;
	}

	@Override
	public int estimatedEncodingSize() {
		// space for tag, generous VLC length, plus raw data
		return 1 + Format.MAX_VLC_LONG_LENGTH + length;
	}

	/**
	 * Creates a Blob of random bytes of the given length
	 * 
	 * @param random Any Random generator instance
	 * @param length Length of blob to generate in bytes
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
		long chunkStart = i * CHUNK_LENGTH;
		return slice(chunkStart, Math.min(CHUNK_LENGTH, length - chunkStart));
	}

	public void attachContentHash(Hash hash) {
		if (contentHash == null) contentHash = hash;
	}

	@Override
	public boolean isRegularBlob() {
		return true;
	}


	




}