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
	public void ednString(StringBuilder sb) {
		sb.append(value);
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append(value);
	}

	@Override
	public double doubleValue() {
		return (double)value;
	}
	
	/**
	 * Parses a Chracter from a String
	 * @param s
	 * @return CVMChar instance, or null if not valid
	 */
	public static CVMChar parse(String s) {
		int n=s.length();
		
		if (n<2) return null;
		
		if (n==2) {
			return CVMChar.create(s.charAt(1));
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
