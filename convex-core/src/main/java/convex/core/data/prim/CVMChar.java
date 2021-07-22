package convex.core.data.prim;

import convex.core.data.Tag;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.reader.ReaderUtils;
import convex.core.util.Utils;

/**
 * Class for CVM character values.
 * 
 * Chars are 16-bit UTF-16 unsigned integers, and are the elements of Strings CVM.
 */
public final class CVMChar extends APrimitive {

	public static final CVMChar A = CVMChar.create('a');
	
	private final char value;
	
	public CVMChar(char value) {
		this.value=value;
	}
	
	@Override
	public AType getType() {
		return Types.CHARACTER;
	}


	public static CVMChar create(long value) {
		return new CVMChar((char)value);
	}
	
	@Override
	public long longValue() {
		return value;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 1+2;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing to check. Always valid
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.CHAR;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return Utils.writeChar(bs,pos,((char)value));
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
		String s;
		switch(value) {
			case '\n': s = "\\newline"; break;
			case '\r': s = "\\return"; break;
			case ' ':  s = "\\space"; break;
			case '\t': s = "\\tab"; break;
			default:   s = "\\" + Character.toString(value);
		}
		sb.append(s);
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
		return Character.toString(value);
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
		return value;
	}
	
	@Override
	public byte getTag() {
		return Tag.CHAR;
	}
}
