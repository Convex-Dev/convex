package convex.core.data;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import convex.core.data.type.Types;
import convex.core.exceptions.BadFormatException;
import convex.core.util.ErrorMessages;
import convex.core.util.Utils;

/**
 * General purpose immutable wrapper for byte array data.
 * 
 * Can be encoded fully as a single Cell if 4096 bytes or less, otherwise needs to be
 * structures as a BlobTree.
 * 
 * Encoding format is:
 * - Tag.BLOB tag byte
 * - VLC encoded Blob length in bytes (one or two bytes describing a length in range 0..4096)
 * - Byte data of the given length
 */
public class Blob extends AArrayBlob {
	public static final Blob EMPTY = Cells.intern(wrap(Utils.EMPTY_BYTES));
	public static final Blob SINGLE_ZERO = Cells.intern(wrap(new byte[] {0}));
	public static final Blob SINGLE_ONE = Cells.intern(wrap(new byte[] {1}));
	public static final Blob SINGLE_A =wrap(new byte[] {0x41});

	public static final Blob NULL_ENCODING = Blob.wrap(new byte[] {Tag.NULL});
	
	public static final int CHUNK_LENGTH = 4096;
	
	private static final byte[] EMPTY_CHUNK_BYTES=new byte[CHUNK_LENGTH];
 	
	public static final Blob EMPTY_CHUNK = Cells.intern(wrap(EMPTY_CHUNK_BYTES));

	private Blob(byte[] bytes, int offset, int length) {
		super(bytes, offset, length);
	}

	/**
	 * Creates a new Blob using a copy of the specified byte range
	 * 
	 * @param data Byte array
	 * @param offset Start offset in the byte array
	 * @param length Number of bytes to take from data array
	 * @return The new Data object
	 */
	public static Blob create(byte[] data, int offset, int length) {
		if (length <= 0) {
			if (length == 0) return EMPTY;
			throw new IllegalArgumentException(ErrorMessages.negativeLength(length));
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
	 * Parses String input as a Blob. Converts from hex.
	 * 
	 * @param data Byte array
	 * @return Blob with the same byte contents as the given array
	 */
	public static Blob parse(String data) {
		ABlob b=Blobs.parse(data);
		if (b==null) return null;
		return b.toFlatBlob();
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
		if (length < 0) throw new IllegalArgumentException(ErrorMessages.negativeLength(length));
		if ((offset < 0) || (offset + length > data.length))
			throw new IndexOutOfBoundsException(ErrorMessages.badRange(offset, offset+length));
		if (length==0) return Blob.EMPTY;
		Blob b= new Blob(data, offset, length);
		
		// optimisation to re-use Blob encoding if present
		if ((offset>=2)&&(length<128)&&(data[offset-1]==(byte)length)&&(data[offset-2]==Tag.BLOB)) {
			b.attachEncoding(Blob.wrap(data,offset-2,length+2));
		}
		return b;
	}

	@Override
	public Blob toFlatBlob() {
		return this;
	}

	@Override
	public Blob slice(long start, long end) {
		if (start < 0) return null;
		if (end > this.count) return null;
		long length=end-start;
		int size=(int)length;
		if (size!=length) return null; // int overflow, too big for valid Blob slice!
		if (length < 0) return null;
		if (length == 0) return EMPTY;
		if (length==this.count) return this;
		return Blob.wrap(store, Utils.checkedInt(start + offset), size);
	}
	
	@Override
	public Blob slice(long start) {
		return slice(start, count());
	}

	@Override
	public boolean equals(ABlob a) {
		if (a==this) return true;
		if (a instanceof AArrayBlob) return equals((AArrayBlob) a);
		long n=count();
		if (a.count()!=n) return false;
		if (!(a.getType()==Types.BLOB)) return false;
		if (n<=CHUNK_LENGTH) {
			return a.equalsBytes(this.store, this.offset);
		} else {
			// this must be a non-canonical Blob
			// we coerce encoding, since might have hash, and probably needed anyway
			return getEncoding().equals(a.getEncoding());
		}
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
		if (bs.length==0) return EMPTY;
		return wrap(bs);
	}
	
	public static Blob forByte(byte b) {
		return wrap(new byte[] {b});
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



	/**
	 * Fast read of a Blob from its encoding inside another Blob object.
	 * Assumes count is correct at start of encoding (pos+1)
	 * 
	 * @param source Source Blob object.
	 * @param pos Position in source to start reading from (location of tag byte)
	 * @param count Length in bytes to take from the source Blob
	 * @return Blob read from the source
	 * @throws BadFormatException If encoding is invalid
	 */
	public static Blob read(Blob source, int pos, long count) throws BadFormatException {
		if (count==0) return EMPTY; // important! Don't want to allocate new empty Blobs or mess with EMPTY encoding
		if (count>CHUNK_LENGTH) throw new BadFormatException("Trying to read flat blob with count = " +count);
		
		// compute data length, excluding tag and encoded length
		int headerLength = (1 + Format.getVLQCountLength(count));
		long start = pos+ headerLength;
		if (start+count>source.count()) {
			throw new BadFormatException("Insufficient bytes to read Blob required count =" + count);
		}

		Blob result= source.slice(start , start+count);
		if (result==null) throw new IllegalArgumentException("Failed to slice Blob source");
		if (source.byteAtUnchecked(pos)==Tag.BLOB) {
			// Only attach encoding if we were reading a genuine Blob
			result.attachEncoding(source.slice(pos,pos+(headerLength+count)));
		}
		return result;
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		if (count > CHUNK_LENGTH) {
			// We aren't canonical, so need to encode canonical representation
			return getCanonical().encodeRaw(bs, pos);
		} else {
			pos=super.encodeRaw(bs,pos);
			return pos;
		}
	}
	
	@Override
	public int estimatedEncodingSize() {
		// space for tag, generous VLC length, plus raw data
		return 1 + Format.MAX_VLQ_LONG_LENGTH + size();
	}
	
	/**
	 * Maximum encoding size for a regular Blob
	 */
	public static final int MAX_ENCODING_LENGTH=1+Format.getVLQCountLength(CHUNK_LENGTH)+CHUNK_LENGTH;


	@Override
	public boolean isCanonical() {
		return count <= Blob.CHUNK_LENGTH;
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
		if ((i == 0) && (count <= CHUNK_LENGTH)) return this;
		long start = i * CHUNK_LENGTH;
		long take=Math.min(CHUNK_LENGTH, count - start);
		return slice(start, start+take);
	}

	public void attachContentHash(Hash hash) {
		if (contentHash==null) contentHash = hash;
	}

	@Override
	public ABlob toCanonical() {
		if (isCanonical()) return this;
		return Blobs.toCanonical(this);
	}
	

}