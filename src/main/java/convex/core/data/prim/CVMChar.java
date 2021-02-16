package convex.core.data.prim;

import convex.core.data.Tag;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Class for CVM character values.
 * 
 * Chars are 16-bit UTF-16 unsigned integers, and are the elements of Strings CVM.
 */
public final class CVMChar extends APrimitive {

	private final char value;
	
	public CVMChar(char value) {
		this.value=value;
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
	public Class<?> numericType() {
		return Long.class;
	}

	@Override
	public double doubleValue() {
		return (double)value;
	}
	
	public static CVMChar parse(String s) {
		return create(Long.parseLong(s));
	}

	public char charValue() {
		return value;
	}
	
	@Override
	public byte getTag() {
		return Tag.CHAR;
	}
}
