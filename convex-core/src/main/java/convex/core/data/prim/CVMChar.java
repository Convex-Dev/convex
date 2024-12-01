package convex.core.data.prim;

import convex.core.Constants;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Strings;
import convex.core.data.Tag;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.reader.ReaderUtils;
import convex.core.util.Bits;

/**
 * Class for CVM Character values.
 * 
 * Characters are Unicode code points, and can be used to construct Strings on the CVM.
 * Limited to range 0 .. 0x10ffff as per Unicode standard
 */
public final class CVMChar extends APrimitive implements Comparable<CVMChar> {
	public static int MAX_CODEPOINT=0x10ffff; // 21 bits max Unicode value
	public static CVMChar MAX_VALUE=create(MAX_CODEPOINT); // 21 bits max Unicode value
	
	public static CVMChar BAD_CHARACTER=create(0xFFFD);
	
	private static final int CACHE_SIZE=256;
	
	private static final CVMChar[] cache=new CVMChar[CACHE_SIZE];
	
	static {
		for (int i=0; i<CACHE_SIZE; i++) {
			cache[i]=new CVMChar(i);
		}
	}

	/**
	 * Singleton instance representing the NULL character (code point zero)
	 */
	public static final CVMChar ZERO = CVMChar.create(0);

	/**
	 * Maximum number of UTF-8 bytes required to represent a {@link CVMChar}
	 */
	public static final int MAX_UTF_BYTES = 4;
	
	/**
	 * The Unicode code point of this character
	 */
	private final int value;
	
	private CVMChar(int value) {
		this.value=value;
		this.memorySize=Format.FULL_EMBEDDED_MEMORY_SIZE;
	}
	
	@Override
	public AType getType() {
		return Types.CHARACTER;
	}

	/**
	 * Gets a {@link CVMChar} for the given Unicode code point, or null if not valid
	 * @param value Unicode code point for the character
	 * @return CVMChar instance, or null if not valid
	 */
	public static CVMChar create(long value) {
		if (value<0) return null; // invalid negative number
		if (value<CACHE_SIZE) return cache[(int)value];
		if (value>MAX_CODEPOINT) return null;
		return new CVMChar((int)value);
	}
	
	/**
	 * Gets a {@link CVMChar} from a UTF-8 representation
	 * @param b Representation of a single character (UTF-8)
	 * @return CVMChar instance, or null if not valid
	 */
	public static CVMChar fromUTF8(ABlobLike<?> b) {
		long n=b.count();
		if ((n==0)||(n>4)) return null;
		int v=(int)b.longValue()<<((4-n)*8);
		int cp=CVMChar.codepointFromUTFInt(v);
		if (CVMChar.utfLength(cp)!=n)return null;
		return CVMChar.create(cp);
	}
	
	/**
	 * Gets the Long value of this char, equal to the Unicode code point
	 */
	@Override
	public long longValue() {
		return 0xffffffffl&value;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 4;
	}
	
	/**
	 * Get the number of UTF-8 bytes as encoded within the encoding tag
	 * @param tag Tag byte
	 * @return Number of bytes in range 1-4
	 */
	public static int byteCountFromTag(byte tag) {
		return (tag&0x03)+1;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing to check. Always valid
	}
	
	/**
	 * Gets a code point value from bytes encoded in a Java integer (starting from high byte)
	 * @param utf UTF-8 encoded value in an integer, first byte in high byte.
	 * @return Unicode code point, or -1 if not valid UTF-8
	 */
	public static int codepointFromUTFInt(int utf) {
		byte b = (byte)(utf>>24);
		if (b >= 0)
			return b; // Valid ASCII
		if ((b & 0xC0) != 0xC0)
			return -1; // invalid first byte
		int len = 2;
		if ((b & 0x20) == 0x20) {
			len += 1; // at least 3 bytes
			if ((b & 0x10) == 0x10)
				len += 1; // 4 bytes
		}
		if (((b << len) & 0x80) != 0)
			return -1; // bad bit after length, should be 0

		// get bits from high byte
		int result = b & (0x7f >> len);
		for (int i = 1; i < len; i++) {
			byte c = (byte)(utf>>(24-8*i));
			if ((c & 0xC0) != 0x80)
				return -1; // should start with 10, 0xff will be invalid
			result = (result << 6) | (c & 0x3F);
		}
		if (!Character.isValidCodePoint(result))
			return -1;
		return result;
	}
	
	
	/**
	 * Gets the length in bytes needed to express the character in an Encoding
	 * @param c Code point value
	 * @return Number of bytes needed to encode code point (following tag)
	 */
	private static int encodedCharLength(int c) {
		if ((c&0xffff0000)==0) {
			return ((c&0x0000ff00)==0)?1:2;
		} else {
			return ((c&0xff000000)==0)?3:4;
		}
	}
	
	/** 
	 * Gets the UTF-8 length in bytes for this CVMChar
	 * @param c Code point value
	 * @return UTF lenth or -1 if not a valid Unicode value
	 */
	public static int utfLength(long c) {
		if (c<0) return -1;
		if (c<=0x7f) return 1;
		if (c<=0x7ff) return 2;
		if (c<=0xffff) return 3;
		if (c<=MAX_CODEPOINT) return 4;
		return -1;
	}
	
	/**
	 * Reads char data from Blob
	 * @param len Length in UTF-8 bytes
	 * @param blob Blob to read from
	 * @param pos Position of tag
	 * @return CVMChar instance
	 * @throws BadFormatException if any format error
	 */
	public static CVMChar read(int len, Blob blob, int pos) throws BadFormatException {
		CVMChar result=readRaw(len,blob,pos+1); // read 
		result.attachEncoding(blob.slice(pos, pos+1+len));
		return result;
	}
	
	/**
	 * Reads raw char data from Blob
	 * @param len Length in UTF=8 bytes
	 * @param blob Blob to read from
	 * @param pos Position of first UTF-9 byte
	 * @return CVMChar instance
	 * @throws BadFormatException if any format error
	 */
	private static CVMChar readRaw(int len, Blob blob, int pos) throws BadFormatException {
		int value=0xff000000; // High byte should be shifted away, here to catch errors
		for (int i=0; i<len;i++) {
			if (value==0) throw new BadFormatException("Leading zero in CVMChar encoding");
			byte b=blob.byteAt(pos+i);
			value=(value<<8)+(b&0xFF);
		}
		CVMChar result=create(value);
		if (result==null) throw new BadFormatException("CVMChar out of Unicode range");
		return result;
	}


	@Override
	public int encode(byte[] bs, int pos) {
		int len=encodedCharLength(value);
		bs[pos++]=getTag();
		return encodeRaw(len,bs,pos);
	}

	private int encodeRaw(int len,byte[] bs, int pos) {
		for (int i=0; i<len; i++) {
			bs[pos+i]=(byte)((value>>((len-1-i)*8))&0xff);
		}
		return pos+len;
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		throw new UnsupportedOperationException("Encoding requires a length in bytes");
	}

	@Override
	public boolean print(BlobBuilder bb, long limit) {
		// Prints like EDN.
		// Characters are preceded by a backslash: \c, \newline, \return, \space and
		// \tab yield
		// the corresponding characters.
		// Unicode characters are represented as in Java.
		// Backslash cannot be followed by whitespace.
		//
		switch(value) {
			case '\n': bb.append("\\newline"); break;
			case '\r': bb.append("\\return"); break;
			case ' ':  bb.append("\\space"); break;
			case '\t': bb.append("\\tab"); break;
			default:  {
				bb.append('\\');
				if (Character.isBmpCodePoint(value)) {
					bb.append((char)value);
				} else {
					bb.append(toUTFBytes());
				}
			}
		}
		return bb.check(limit);
	}

	/**
	 * Returns the Java String representation of this CVMChar. Returns a bad character representation in
	 * the case that the UTF code point of this Character is invalid
	 * 
	 * Different from {@link #print() print()} which returns a readable representation.
	 *
	 * For instance, on CVMChar \a, this methods returns "a" while {@link #print() print()} returns "\a".
	 */
	@Override
	public String toString() {
		if (Character.isValidCodePoint(value)) {
			return Character.toString(value);
		} else {
			return Constants.BAD_CHARACTER_STRING;
		}
	}

	@Override
	public double doubleValue() {
		return (double)value;
	}
	
	/**
	 * Parses a Character from a Java String, as interpreted by the Reader e.g. "\newline" or "\c"
	 * @param s String to parse
	 * @return CVMChar instance, or null if not valid
	 */
	public static CVMChar parse(String s) {
		int n=s.length();
		
		if (n<2) return null;
		
		if (n==2) {
			return CVMChar.create(s.charAt(1));
		}
		
		if (s.charAt(1)=='u') {
			if (n==6) {
				char c = (char) Long.parseLong(s.substring(2),16);
				return CVMChar.create(c);
			}
		}
		
		s=s.substring(1);
		CVMChar maybeSpecial= ReaderUtils.specialCharacter(s);
		if (maybeSpecial!=null) return maybeSpecial;
		long cp=s.codePointAt(0);
		return CVMChar.create(cp);
	}
	
	@Override
	public byte getTag() {
		return (byte) (Tag.CHAR_BASE+(encodedCharLength(value)-1));
	}

	/**
	 *  Gets the Java char value of this CVM Character. 
	 *  
	 *  Not all Unicode code points fit in a JVM char, a "bad character" value is used as replacement if this is not possible.
	 * @return Java Char, or a special bad character if not valid.
	 */
	public char charValue() {
		if (Character.isBmpCodePoint(value)) {
			return (char)value;
		} else {
			return Constants.BAD_CHARACTER;
		}
	}

	/**
	 * Converts this Character to a Blob with its UTF-8 byte representation
	 * @return byte[] array containing UTF-8 bytes
	 */
	public byte[] toUTFBytes() {
		int n=utfLength(value);
		if (n<=0) throw new Error("Shouldn't happen: CVMChar out of range: "+value);
		byte[] bs=new byte[n];
		if (value<128) {
			bs[0]=(byte)value;
			return bs;
		}
		bs[0]=(byte)((0xff00>>(n))|(value>>((n-1)*6)));
		for (int i=1; i<n; i++) {
			bs[i]=(byte)(0x80|(0x3f&(value>>((n-1-i)*6))));
		}
		return bs;
	}
	
	/**
	 * Gets the Blob representation of this Character in UTF-8
	 * @return 1-4 Bytes Blob containing UTF-8 representation of this Character
	 */
	public Blob toUTFBlob() {
		return Blob.wrap(toUTFBytes());
	}

	@Override
	public AString toCVMString(long limit) {
		if (limit<=0) return null;
		return Strings.create(toUTFBlob());
	}

	/**
	 * Gets the Unicode code point for this Character
	 * @return Code point as an int value
	 */
	public int getCodePoint() {
		return value;
	}

	@Override
	public int compareTo(CVMChar o) {
		return Integer.compare(value, o.value);
	}

	@Override public boolean equals(ACell a) {
		if (!(a instanceof CVMChar)) return false;
		return value==((CVMChar)a).value;
	}
	
	public boolean equals(CVMChar a) {
		if (a==null) return false;
		return value==a.value;
	}
	
	@Override
	public int hashCode() {
		return Bits.hash32(value);
	}





}
