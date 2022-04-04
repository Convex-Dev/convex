package convex.core.data;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Class representing a short CVM string.
 */
public class StringShort extends AString {
	
	/**
	 * Length of longest StringShort value that is embedded
	 * 
	 * Basically max embedded length minus tag byte and 2-byte length
	 */
	public static final int MAX_EMBEDDED_STRING_LENGTH=Format.MAX_EMBEDDED_LENGTH-3;

	/**
	 * Length of longest StringShort value in bytes. Use Blob as base.
	 */
	public static final int MAX_LENGTH=Blob.CHUNK_LENGTH;

	public static final int MAX_ENCODING_LENGTH = 1+Format.getVLCLength(MAX_LENGTH)+MAX_LENGTH; // Max 4096 bytes

	
	private final Blob data;
	
	protected StringShort(Blob data) {
		super(data.length);
		this.data=data;
	}
	
	protected StringShort(byte[] data) {
		super(data.length);
		this.data=Blob.wrap(data);
	}
	
	protected StringShort(byte[] data, int offset, int length) {
		super(length);
		this.data=Blob.wrap(data,offset,length);
	}
	
	/**
	 * Creates a StringShort instance from a regular Java String
	 * 
	 * @param string String to wrap as StringShort
	 * @return StringShort instance, or null if String is of invalid size
	 */
	public static StringShort create(String string) {
		byte[] bs=string.getBytes(StandardCharsets.UTF_8);
		return new StringShort(bs);
	}
	
	/**
	 * Creates a StringShort instance from a Blob of UTF-8 data. Shares underlying array.
	 * 
	 * @param b Array Blob to convert to StringShort
	 * @return StringShort instance
	 */
	public static StringShort create(AArrayBlob b) {
		if (b.count()==0) return Strings.EMPTY;
		return new StringShort(b.toFlatBlob());
	}


	@Override
	public int charAt(long index) {
		byte b=data.byteAt(index);
		if (b>=0) return b; // Valid ASCII
		if ((b&0xC0)!=0xC0) return -1; //invalid first byte
		int len=2; 
		if ((b&0x20)==0x20) {
			len+=1; // at least 3 bytes
			if ((b&0x10)==0x10) len+=1; // 4 bytes
		}
		if (((b<<len)&0x80)!=0) return -1; // bad bit after length, should be 0
		
		// get bits from high byte
		int result=b&(0x7f>>len);
		for (int i=1; i<len; i++) {
			if ((index+i)>=count()) return -1; // end of string
			byte c=data.byteAt(index+i);
			if ((c&0xC0)!=0x80) return -1; // should start with 10
			result=(result<<6)|(c&0x3F);
		}
		if (!Character.isValidCodePoint(result)) return -1;
		return result;
	}

	@Override
	public byte byteAt(long index) {
		if ((index<0)||(index>=length)) return Strings.EXCESS_BYTE;
		return data.byteAt(index);
	}

	@Override
	public StringShort subString(long start, long end) {
		return new StringShort(data.slice(start, end-start));
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (length>MAX_LENGTH) throw new InvalidDataException("StringShort too long: " +length,this);
		if (length!=data.length) throw new InvalidDataException("Wrong String length!",this);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return data.encodeRaw(bs, pos);
	}
	
	@Override
	public int encodeRawData(byte[] bs, int pos) {
		return data.encodeRawData(bs, pos);
	}

	@Override
	public int estimatedEncodingSize() {
		return 3+(int)length;
	}

	@Override
	public boolean isCanonical() {
		return (length<=MAX_LENGTH);
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}
	
	@Override
	public boolean isEmbedded() {
		return length<=MAX_EMBEDDED_STRING_LENGTH;
	}

	@Override
	public int getRefCount() {
		return 0;
	}

	/**
	 * Read a StringShort from a ByteBuffer. Assumes tag and length already read and correct.
	 * 
	 * @param length Length in number of chars to read
	 * @param bb ByteBuffer to read from
	 * @return AString instance
	 */
	public static AString read(long length, ByteBuffer bb) {
		byte[] data=new byte[Utils.checkedInt(length)];
		bb.get(data);
		return new StringShort(data);
	}
	
	@Override
	public Blob toBlob() {
		return data;
	}
	
	@Override 
	public boolean equals(ACell a) {
		if (a instanceof StringShort) {
			return equals((StringShort)a);
		}
		return false;
	}
	
	public boolean equals(StringShort a) {
		return data.equals(a.data);
	}

	@Override
	public int compareTo(AString o) {
		return data.compareTo(o.toBlob());
	}

	@Override
	public ACell toCanonical() {
		if (length<=MAX_LENGTH) return this;
		return Strings.create(data.toCanonical());
	}
	
	@Override
	protected void writeToBuffer(ByteBuffer bb) {
		data.writeToBuffer(bb);
	}

}
