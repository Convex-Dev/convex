package convex.core.data;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import convex.core.exceptions.InvalidDataException;
import convex.core.util.Errors;
import convex.core.util.Text;
import convex.core.util.Utils;

/**
 * Class representing a short CVM string, backed by a flat Blob
 * 
 * Used for most small strings, and acts as the leaf chunk for StringTrees
 */
public class StringShort extends AString {

	/**
	 * Length of longest StringShort value that is embedded
	 * 
	 * Basically max embedded length minus tag byte and 2-byte length
	 */
	public static final int MAX_EMBEDDED_STRING_LENGTH = Format.MAX_EMBEDDED_LENGTH - 3;

	/**
	 * Length of longest StringShort value in bytes. Use Blob as base.
	 */
	public static final int MAX_LENGTH = Blob.CHUNK_LENGTH;

	public static final int MAX_ENCODING_LENGTH = 1 + Format.getVLCLength(MAX_LENGTH) + MAX_LENGTH; // Max 4096 bytes

	private final Blob data;

	/**
	 * The canonical empty String
	 */
	public static final StringShort EMPTY = new StringShort(Blob.EMPTY);

	protected StringShort(Blob data) {
		super(data.length);
		this.data = data;
	}

	protected StringShort(byte[] data) {
		super(data.length);
		this.data = Blob.wrap(data);
	}

	protected StringShort(byte[] data, int offset, int length) {
		super(length);
		this.data = Blob.wrap(data, offset, length);
	}

	/**
	 * Creates a StringShort instance from a regular Java String
	 * 
	 * @param string String to wrap as StringShort
	 * @return StringShort instance, or null if String is of invalid size
	 */
	public static StringShort create(String string) {
		if (string.length()==0) return EMPTY;
		byte[] bs = string.getBytes(StandardCharsets.UTF_8);
		return new StringShort(bs);
	}

	/**
	 * Creates a StringShort instance from a Blob of UTF-8 data. Shares underlying
	 * array.
	 * 
	 * @param b Array Blob to convert to StringShort
	 * @return StringShort instance
	 */
	public static StringShort create(AArrayBlob b) {
		if (b.count() == 0)
			return StringShort.EMPTY;
		return new StringShort(b.toFlatBlob());
	}

	@Override
	public byte byteAt(long index) {
		if ((index < 0) || (index >= length))
			return Strings.EXCESS_BYTE;
		return data.byteAt(index);
	}
	
	@Override
	public int intAt(long index) {
		int r=0;
		for (int i=0; i<4; i++) {
			long ix=index+i;
			if ((ix < 0) || (ix >= length)) {
				r|=(0xFF&Strings.EXCESS_BYTE)<<(8*(3-i));
			} else {
				r|=(0xff&data.byteAt(ix))<<(8*(3-i));
			}
		}
		return r;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (length > MAX_LENGTH)
			throw new InvalidDataException("StringShort too long: " + length, this);
		if (length != data.length)
			throw new InvalidDataException("Wrong String length!", this);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return data.encodeRaw(bs, pos);
	}

	@Override
	public int writeRawData(byte[] bs, int pos) {
		return data.getBytes(bs, pos);
	}

	@Override
	public int estimatedEncodingSize() {
		return 3 + (int) length;
	}

	@Override
	public boolean isCanonical() {
		return (length <= MAX_LENGTH);
	}

	@Override
	public final boolean isCVMValue() {
		return true;
	}

	@Override
	public boolean isEmbedded() {
		return length <= MAX_EMBEDDED_STRING_LENGTH;
	}

	@Override
	public int getRefCount() {
		return 0;
	}

	/**
	 * Read a StringShort from a ByteBuffer. Assumes tag and length already read and
	 * correct.
	 * 
	 * @param length Length in number of chars to read
	 * @param bb     ByteBuffer to read from
	 * @return AString instance
	 */
	public static AString read(long length, ByteBuffer bb) {
		byte[] data = new byte[Utils.checkedInt(length)];
		bb.get(data);
		return new StringShort(data);
	}
	
	public static StringShort read(long length, Blob blob, int pos) {
		int len=Utils.checkedInt(length);
		int dataOffset=pos+1+Format.getVLCLength(length);
		byte[] data = new byte[len];
		System.arraycopy(blob.getInternalArray(), blob.getInternalOffset()+dataOffset, data, 0, len);
		StringShort result= new StringShort(data);
		result.attachEncoding(blob.slice(pos,dataOffset+len));
		return result;
	}

	@Override
	public Blob toBlob() {
		return data;
	}

	@Override
	public boolean equals(ACell a) {
		if (a instanceof StringShort) {
			return equals((StringShort) a);
		}
		return false;
	}

	public boolean equals(StringShort a) {
		if (a==this) return true;
		return data.equals(a.data);
	}

	@Override
	public int compareTo(AString o) {
		return data.compareTo(o.toBlob());
	}

	@Override
	public ACell toCanonical() {
		if (length <= MAX_LENGTH)
			return this;
		return Strings.create(data.toCanonical());
	}

	@Override
	public AString slice(long start, long end) {
		Blob newData=data.slice(start, end);
		if (data==newData) return this;
		if (newData==null) return null;
		return create(newData);
	}

	@Override
	protected void printEscaped(BlobBuilder sb, long start, long end) {
		long n=count();
		if ((start<0)||(start>end)||(end>n)) throw new IllegalArgumentException(Errors.badRange(start, end));
		for (long i=start; i<end; i++) {
			byte b=data.byteAtUnchecked(i);
			Text.writeEscapedByte(sb,b);
		}
		return;
	}

}
