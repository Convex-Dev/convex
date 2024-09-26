package convex.core.data;

import java.nio.charset.StandardCharsets;

import convex.core.data.prim.CVMChar;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.InvalidDataException;
import convex.core.text.Text;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Class representing a short CVM string, backed by a flat Blob
 * 
 * Used for most small strings, and acts as the leaf chunk for StringTrees
 */
public final class StringShort extends AString {

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
	public static final StringShort EMPTY = Cells.intern(new StringShort(Blob.EMPTY));

	protected StringShort(Blob data) {
		super(data.count);
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
		if (length != data.count)
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
	public <R extends ACell> Ref<R> getRef(int i) {
		if (!isCanonical()) return super.getRef(i);
		throw new IndexOutOfBoundsException(i);
	}

	@Override
	public ACell updateRefs(IRefFunction func) {
		if (!isCanonical()) return super.updateRefs(func);
		return this;
	}
	
	@Override
	public int getRefCount() {
		if (!isCanonical()) return super.getRefCount();
		return 0;
	}
	
	@Override
	public int getBranchCount() {
		if (!isCanonical()) return super.getRefCount();
		return 0;
	}
	
	/**
	 * Read a StringShort from an encoding. Assumes tag and length already validated. 
	 * @param length Length of string in UTF-8 bytes
	 * @param blob Source of encoding
	 * @param pos Position of encoding start (i.e. tag)
	 * @return String instance
	 */
	public static StringShort read(long length, Blob blob, int pos) {
		int len=Utils.checkedInt(length);
		int dataOffset=pos+1+Format.getVLCCountLength(length);
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
	public boolean equals(AString b) {
		if (b instanceof StringShort) {
			return equals((StringShort) b);
		}
		AString c=b.toCanonical();
		if (b==c) return false;
		return equals(c);
	}

	public final boolean equals(StringShort a) {
		if (a==this) return true;
		return data.equals(a.data);
	}

	@Override
	public int compareTo(ABlobLike<?> o) {
		return data.compareTo(o);
	}

	@Override
	public AString toCanonical() {
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
			if (b>=0) {
				// ASCII range, might be escape character
				Text.writeEscapedByte(sb,b);
			} else {
				int cp=charAt(i);
				if (cp<0) {
					sb.append(CVMChar.BAD_CHARACTER);
					i+=1; // skip one byte? or should we error correct?
				} else {
					// need to copy exactly one UTF character
					int len=CVMChar.utfLength(cp);
					for (int j=0; j<len; j++) {
						sb.append(byteAt(i+j));
					}
					i+=len-1;
				}
			}
		}
		return;
	}

	@Override
	public boolean equalsBytes(ABlob b) {
		return data.equalsBytes(b);
	}

	@Override
	public long longValue() {
		return data.longValue();
	}
	
	@Override
	public String toString() {
		byte [] bytes=data.getInternalArray();
		if (bytes.length!=data.count()) bytes=data.getBytes(); // need a copy if not fully packed
		return new String(bytes,StandardCharsets.UTF_8);
	}

}
