package convex.core.data.prim;

import convex.core.data.INumeric;
import convex.core.data.Tag;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Class for CVM double floating-point values.
 * 
 * Follows the Java standard / IEEE 784 spec.
 */
public final class CVMDouble extends APrimitive implements INumeric {

	public static final CVMDouble ZERO = CVMDouble.create(0.0);
	public static final CVMDouble NEGATIVE_ZERO = CVMDouble.create(-0.0);
	public static final CVMDouble ONE = CVMDouble.create(1.0);
	public static final CVMDouble MINUS_ONE = CVMDouble.create(-1.0);

	public static final CVMDouble NaN = CVMDouble.create(Double.NaN);
	public static final CVMDouble POSITIVE_INFINITY = CVMDouble.create(Double.POSITIVE_INFINITY);
	public static final CVMDouble NEGATIVE_INFINITY = CVMDouble.create(Double.NEGATIVE_INFINITY);
	
	private final double value;
	
	private static final long RAW_NAN_BITS=0x7ff8000000000000L;
	
	public static final int MAX_ENCODING_SIZE = 9;
	
	public CVMDouble(double value) {
		this.value=value;
	}

	public static CVMDouble create(double value) {
		// We need to use a canonical NaN value (0x7ff8000000000000L);
		if (Double.isNaN(value)) value=Double.NaN;
		return new CVMDouble(value);
	}
	
	@Override
	public AType getType() {
		return Types.DOUBLE;
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
	public CVMDouble signum() {
		if (value>0.0) return CVMDouble.ONE;
		if (value<0.0) return CVMDouble.MINUS_ONE;
		if (Double.isNaN(value)) return NaN; // NaN special case
		return this;
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
		sb.append(toString());
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

	@Override
	public INumeric toStandardNumber() {
		return this;
	}

	public static CVMDouble read(double value) throws BadFormatException {
		// Need to check for non-canonical NaN values
		if (Double.isNaN(value)) {
			if (Double.doubleToRawLongBits(value)!=RAW_NAN_BITS) {
				throw new BadFormatException("Non-canonical NaN value");
			}
		}
		return create(value);
	}

}
