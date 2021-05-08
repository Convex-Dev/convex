package convex.core.data.prim;

import convex.core.data.ACell;
import convex.core.data.INumeric;
import convex.core.data.Tag;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Class for CVM double floating-point values.
 * 
 * Follows the Java standard / IEEE 784 spec.
 */
public final class CVMDouble extends APrimitive implements INumeric {

	public static final ACell ZERO = CVMDouble.create(0.0);
	public static final ACell NaN = CVMDouble.create(Double.NaN);
	public static final ACell POSITIVE_INFINITY = CVMDouble.create(Double.POSITIVE_INFINITY);
	public static final ACell NEGATIVE_INFINITY = CVMDouble.create(Double.NEGATIVE_INFINITY);
	
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
	public CVMLong toLong() {
		return CVMLong.create(longValue());
	}

	@Override
	public CVMDouble toDouble() {
		return this;
	}
	
	@Override
	public CVMLong signum() {
		if (value>0.0) return CVMLong.ONE;
		if (value<0.0) return CVMLong.MINUS_ONE;
		if (Double.isNaN(value)) return null; // NaN special case
		return CVMLong.ZERO;
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
		sb.append(toString());
	}
	
	@Override
	public String toString() {
		if (Double.isInfinite(value)) {
			if (value>0.0) {
				return "##Inf";
			} else {
				return "##-Inf";
			}
		} else if (Double.isNaN(value)) {
			return "##NaN";
		} else {
			return Double.toString(value);
		}
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
	
	@Override
	public byte getTag() {
		return Tag.DOUBLE;
	}

}
