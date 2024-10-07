package convex.core.data.prim;

import java.math.BigDecimal;
import java.math.BigInteger;

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
import convex.core.util.Bits;
import convex.core.util.Utils;

/**
 * Class for CVM double floating-point values.
 * 
 * Follows the Java standard / IEEE 784 spec.
 */
public final class CVMDouble extends ANumeric {

	public static final CVMDouble ZERO = new CVMDouble(0.0);
	public static final CVMDouble NEGATIVE_ZERO = new CVMDouble(-0.0);
	public static final CVMDouble ONE = new CVMDouble(1.0);
	public static final CVMDouble MINUS_ONE = new CVMDouble(-1.0);

	public static final CVMDouble NaN = new CVMDouble(Double.NaN);
	public static final CVMDouble POSITIVE_INFINITY = new CVMDouble(Double.POSITIVE_INFINITY);
	public static final CVMDouble NEGATIVE_INFINITY = new CVMDouble(Double.NEGATIVE_INFINITY);
	
	private final double value;
	
	private static final long RAW_NAN_BITS=0x7ff8000000000000L;
	
	public static final int MAX_ENCODING_LENGTH = 9;
	
	private CVMDouble(double value) {
		this.value=value;
		this.memorySize=Format.FULL_EMBEDDED_MEMORY_SIZE;
	}

	/**
	 * Creates a CVMDouble. Forces NaNs to be canonical instance.
	 * @param value Double value to wrap
	 * @return CVMDouble value
	 */
	public static CVMDouble create(double value) {
		// We must use a canonical NaN value (0x7ff8000000000000L);
		if (Double.isNaN(value)) {
			return CVMDouble.NaN;
		}
		return new CVMDouble(value);
	}
	
	public static CVMDouble unsafeCreate(double value) {
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
		if (!Double.isNaN(value)) return this;
		return NaN;
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
		// always OK, though might not be CVM value
	}
	
	protected static final boolean isStandardNaN(double value) {
		return Double.doubleToRawLongBits(value)==RAW_NAN_BITS;
	}
	
	@Override public boolean isCVMValue() {
		return true;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.DOUBLE;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		long doubleBits=Double.doubleToRawLongBits(value); // note same as doubleToLongBits assuming we are valid and canonical
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
			long bits=Double.doubleToRawLongBits(value);
			if (bits==RAW_NAN_BITS) {
				return "##NaN";
			} else {
				return "#[1d"+Utils.toHexString(bits)+"]";
			}
		} else {
			return Double.toString(value);
		}
	}

	@Override
	public boolean print(BlobBuilder bb, long limit) {
		bb.append(toString());
		return bb.count()<=limit;
	}

	@Override
	public Class<?> numericType() {
		return Double.class;
	}

	@Override
	public double doubleValue() {
		return value;
	}

	/**
	 * Parses a CVM Double value. 
	 * @param s String to parse
	 * @return CVMDouble value, or null if not parseable as a double
	 */
	public static CVMDouble parse(String s) {
		try {
			double d=Double.parseDouble(s);
			return create(d);
		} catch (NumberFormatException e) {
			return null;
		}
	}
	
	@Override
	public final byte getTag() {
		return Tag.DOUBLE;
	}
	
	public static CVMDouble read(byte tag, Blob blob, int offset) throws BadFormatException {
		if (blob.count()<offset+1+8) throw new BadFormatException("Insufficient blob bytes to read Double");
		long bits=Utils.readLong(blob.getInternalArray(), blob.getInternalOffset()+offset+1,8);
		double d=Double.longBitsToDouble(bits);
		CVMDouble result= unsafeCreate(d);
		result.attachEncoding(blob.slice(offset,offset+1+8));
		return result;
	}

	@Override
	public AString toCVMString(long limit) {
		if (limit<1) return null;
		return Strings.create(toString());
	}
	
	@Override
	public boolean equals(ACell a) {
		if (a==this) return true;
		if (!(a instanceof CVMDouble)) return false;
		return equals((CVMDouble)a);
	}
	
	public boolean equals(CVMDouble a) {
		return longBits()==a.longBits();
	}
	
	private final long longBits() {
		return Double.doubleToRawLongBits(value);
	}

	@Override
	public int hashCode() {
		return Bits.hash32(longBits());
	}

	@Override
	public ANumeric abs() {
		// We use fast path here to save allocations in ~50% of cases
		if (value>0) return this;
		if (value==0) return ZERO; // note: we do this to handle -0.0
		return create(-value);
	}
	
	@Override
	public int compareTo(ANumeric o) {
		return Double.compare(value, o.doubleValue());
	}

	@Override
	public CVMLong ensureLong() {
		// This is not a Long, even if it might be numerically equal to a Long
		return null;
	}

	@Override
	public ANumeric add(ANumeric b) {
		return CVMDouble.create(value+b.doubleValue());
	}

	@Override
	public ANumeric sub(ANumeric b) {
		return CVMDouble.create(value-b.doubleValue());
	}

	@Override
	public ANumeric negate() {
		return CVMDouble.create(-value);
	}

	@Override
	public ANumeric multiply(ANumeric b) {
		return CVMDouble.create(value*b.doubleValue());
	}

	@Override
	public AInteger toInteger() {
		if (!Double.isFinite(value))return null; // catch NaN and infinity
		if ((value<=Long.MAX_VALUE)&&(value>=Long.MIN_VALUE)) {
			return CVMLong.create((long)value);
		}
		
		BigDecimal bd=BigDecimal.valueOf(value);
		BigInteger bi=bd.toBigInteger();
		return CVMBigInteger.wrap(bi);
	}

	@Override
	public boolean isZero() {
		// According to the IEEE 754 standard, negative zero and positive zero should
		// compare as equal with the usual (numerical) comparison operators
		// This is the behaviour in Java
		return value==0.0;
	}




}
