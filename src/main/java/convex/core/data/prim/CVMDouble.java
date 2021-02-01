package convex.core.data.prim;

import convex.core.data.Tag;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Class for CVM double floating-point values.
 * 
 * Follows the Java standard / IEEE 784 spec.
 */
public final class CVMDouble extends APrimitive {

	private final double value;
	
	public CVMDouble(double value) {
		this.value=value;
	}

	public static CVMDouble create(double value) {
		return new CVMDouble(value);
	}
	
	@Override
	public long longValue() {
		return (long)value;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 1+8;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing to check. Always valid
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.DOUBLE;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		long doubleBits=Double.doubleToRawLongBits(value);
		return Utils.writeLong(bs,pos,doubleBits);
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
		return Double.class;
	}

	@Override
	public double doubleValue() {
		return value;
	}

	public static CVMDouble parse(String s) {
		return create(Double.parseDouble(s));
	}

}
