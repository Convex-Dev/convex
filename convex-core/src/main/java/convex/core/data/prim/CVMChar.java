package convex.core.data.prim;

import java.nio.ByteBuffer;

import convex.core.Constants;
import convex.core.data.Format;
import convex.core.data.Symbol;
import convex.core.data.Tag;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.reader.ReaderUtils;
import convex.core.util.Utils;

/**
 * Class for CVM Character values.
 * 
 * Characters are Unicode code point, and can be used to costruct Strings on the CVM.
 */
public final class CVMChar extends APrimitive {

	public static final CVMChar A = CVMChar.create('a');
	
	private final int value;
	
	public CVMChar(int value) {
		this.value=value;
	}
	
	@Override
	public AType getType() {
		return Types.CHARACTER;
	}


	public static CVMChar create(long value) {
		return new CVMChar((int)value);
	}
	
	@Override
	public long longValue() {
		return 0xffffffffl&value;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 1+2;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing to check. Always valid
	}
	
	/**
	 * Gets the length in bytes needed to express the code point
	 * @param c Code point value
	 * @return Number of bytes needed for code point
	 */
	public static int charLength(int c) {
		if ((c&0xffff0000)==0) {
			return ((c&0x0000ff00)==0)?1:2;
		} else {
			return ((c&0xff000000)==0)?3:4;
		}
	}
	
	public static CVMChar read(int len,ByteBuffer bb) throws BadFormatException {
		int value=0xff000000;
		for (int i=0; i<len;i++) {
			if (value==0) throw new BadFormatException("Leading zero in CVMChar encoding");
			byte b=bb.get();
			value=(value<<8)+(b&0xFF);
		}
		return create(value);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		int len=charLength(value);
		bs[pos++]=(byte)(Tag.CHAR+(len-1));
		return encodeRaw(len,bs,pos);
	}

	public int encodeRaw(int len,byte[] bs, int pos) {
		for (int i=0; i<len; i++) {
			bs[pos+i]=(byte)((value>>((len-(i+1))*8))&0xff);
		}
		return pos+len;
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		throw new UnsupportedOperationException("Encoding requires a length in bytes");
	}

	@Override
	public void print(StringBuilder sb) {
		// Prints like EDN.
		// Characters are preceded by a backslash: \c, \newline, \return, \space and
		// \tab yield
		// the corresponding characters.
		// Unicode characters are represented as in Java.
		// Backslash cannot be followed by whitespace.
		//
		switch(value) {
			case '\n': sb.append("\\newline"); break;
			case '\r': sb.append("\\return"); break;
			case ' ':  sb.append("\\space"); break;
			case '\t': sb.append("\\tab"); break;
			default:  {
				sb.append('\\');
				if (Character.isBmpCodePoint(value)) {
					sb.append((char)value);
				} else {
					sb.append(toString());
				}
			}
		}
	}

	/**
	 * Returns the String representation of this CVMChar.
	 * 
	 * Different from {@link #print() print()} which returns a readable representation.
	 *
	 * For instance, on CVMChar \a, this methods returns "a" while {@link #print() print()} returns "\a".
	 */
	@Override
	public String toString() {
		// Usually, primitive types are stringified using `print`. This method 
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
	 * Parses a Character from a String
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
		return ReaderUtils.specialCharacter(s);
	}

	public char charValue() {
		return Character.lowSurrogate(value);
	}
	
	@Override
	public byte getTag() {
		return (byte) (Tag.CHAR+(charLength(value)-1));
	}


}
