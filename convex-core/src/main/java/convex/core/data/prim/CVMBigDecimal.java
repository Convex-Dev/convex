package convex.core.data.prim;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

import convex.core.Constants;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Strings;
import convex.core.data.Tag;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Arbitrary precision Decimal implementation for the CVM.
 * 
 * A CVMBigDecimal wraps a java.math.BigDecimal and provides exact decimal
 * arithmetic. It is canonical if and only if it represents a value that
 * cannot be exactly represented as an integer (i.e. has meaningful fractional digits).
 *
 * Type promotion rules:
 * - BigDecimal op BigDecimal = BigDecimal (exact)
 * - BigDecimal op Integer = BigDecimal (promote integer to BigDecimal)
 * - Integer op BigDecimal = BigDecimal (handled via dispatch)
 * - BigDecimal op Double = Double (promote to double, lossy)
 */
public final class CVMBigDecimal extends ANumeric {

	/**
	 * Maximum byte length for the unscaled value (same limit as BigInteger)
	 */
	private static final int MAX_UNSCALED_BYTELENGTH = Constants.MAX_BIG_INTEGER_LENGTH;

	private final BigDecimal value;

	private CVMBigDecimal(BigDecimal value) {
		this.value = value.stripTrailingZeros();
		this.memorySize = Format.FULL_EMBEDDED_MEMORY_SIZE;
	}

	/**
	 * Creates a CVMBigDecimal from a Java BigDecimal. The value is stripped of
	 * trailing zeros. May return a non-canonical instance if the value is actually
	 * an integer.
	 * 
	 * @param value Java BigDecimal value
	 * @return CVMBigDecimal instance, or null if value is null or too large
	 */
	public static CVMBigDecimal create(BigDecimal value) {
		if (value == null) return null;
		BigDecimal stripped = value.stripTrailingZeros();
		// Check unscaled value size
		BigInteger unscaled = stripped.unscaledValue();
		int byteLen = Utils.byteLength(unscaled);
		if (byteLen > MAX_UNSCALED_BYTELENGTH) return null;
		return new CVMBigDecimal(stripped);
	}

	/**
	 * Creates a canonical CVMBigDecimal from a Java BigDecimal.
	 * Returns null if the value is actually an integer (should be CVMLong or CVMBigInteger instead).
	 * 
	 * @param value Java BigDecimal value
	 * @return Canonical CVMBigDecimal, or null if value is an integer or null or too large
	 */
	public static CVMBigDecimal createCanonical(BigDecimal value) {
		CVMBigDecimal result = create(value);
		if (result == null) return null;
		if (!result.isCanonical()) return null;
		return result;
	}

	/**
	 * Creates a CVMBigDecimal from a string representation.
	 * 
	 * @param s String containing a decimal number (e.g. "3.14", "-0.001", "1E+5")
	 * @return CVMBigDecimal instance, or null if not parseable
	 */
	public static CVMBigDecimal parse(String s) {
		try {
			BigDecimal bd = new BigDecimal(s);
			return create(bd);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Creates a CVMBigDecimal from a double value.
	 * WARNING: This may introduce floating-point representation artifacts.
	 * 
	 * @param d Double value
	 * @return CVMBigDecimal instance, or null if infinite/NaN
	 */
	public static CVMBigDecimal fromDouble(double d) {
		if (!Double.isFinite(d)) return null;
		return create(BigDecimal.valueOf(d));
	}

	/**
	 * Creates a CVMBigDecimal from a long value (scale 0).
	 * 
	 * @param l Long value
	 * @return CVMBigDecimal instance (may not be canonical since it's an integer)
	 */
	public static CVMBigDecimal fromLong(long l) {
		return create(BigDecimal.valueOf(l));
	}

	/**
	 * Creates a CVMBigDecimal from a BigInteger value (scale 0).
	 * 
	 * @param bi BigInteger value
	 * @return CVMBigDecimal instance (may not be canonical since it's an integer)
	 */
	public static CVMBigDecimal fromBigInteger(BigInteger bi) {
		if (bi == null) return null;
		return create(new BigDecimal(bi));
	}

	/**
	 * Gets the underlying Java BigDecimal value.
	 * 
	 * @return Java BigDecimal
	 */
	public BigDecimal getDecimal() {
		return value;
	}

	/**
	 * Gets the scale of this BigDecimal (number of digits after decimal point).
	 * 
	 * @return Scale
	 */
	public int scale() {
		return value.scale();
	}

	/**
	 * Gets the unscaled value of this BigDecimal.
	 * 
	 * @return Unscaled BigInteger value
	 */
	public BigInteger unscaledValue() {
		return value.unscaledValue();
	}

	@Override
	public AType getType() {
		return Types.DECIMAL;
	}

	@Override
	public CVMLong toLong() {
		try {
			return CVMLong.create(value.longValueExact());
		} catch (ArithmeticException e) {
			return null;
		}
	}

	@Override
	public long longValue() {
		return value.longValue();
	}

	@Override
	public CVMDouble toDouble() {
		return CVMDouble.create(value.doubleValue());
	}

	@Override
	public double doubleValue() {
		return value.doubleValue();
	}

	@Override
	public Class<?> numericType() {
		return BigDecimal.class;
	}

	@Override
	public CVMLong signum() {
		return CVMLong.forSignum(value.signum());
	}

	@Override
	public boolean isNegative() {
		return value.signum() < 0;
	}

	@Override
	public boolean isPositive() {
		return value.signum() > 0;
	}

	@Override
	public boolean isNatural() {
		// A decimal is natural if it's a non-negative integer
		if (value.signum() < 0) return false;
		return value.stripTrailingZeros().scale() <= 0;
	}

	@Override
	public boolean isZero() {
		return value.signum() == 0;
	}

	@Override
	public boolean isCanonical() {
		// Canonical if and only if it has meaningful fractional digits
		// i.e. it cannot be exactly represented as an integer
		return value.stripTrailingZeros().scale() > 0;
	}

	@Override
	public ANumeric abs() {
		if (value.signum() >= 0) return this;
		return create(value.abs());
	}

	@Override
	public ANumeric negate() {
		return create(value.negate());
	}

	@Override
	public CVMLong ensureLong() {
		return null; // BigDecimal is never a Long, even if numerically equal
	}

	@Override
	public AInteger toInteger() {
		try {
			BigInteger bi = value.toBigIntegerExact();
			return AInteger.create(bi);
		} catch (ArithmeticException e) {
			return null; // has fractional part
		}
	}

	// ---- Arithmetic operations ----

	@Override
	public ANumeric add(ANumeric b) {
		if (b instanceof CVMBigDecimal bd) {
			return create(value.add(bd.value));
		}
		if (b instanceof AInteger ai) {
			return create(value.add(new BigDecimal(ai.big())));
		}
		// Fall back to double for CVMDouble
		return CVMDouble.create(doubleValue() + b.doubleValue());
	}

	@Override
	public ANumeric sub(ANumeric b) {
		if (b instanceof CVMBigDecimal bd) {
			return create(value.subtract(bd.value));
		}
		if (b instanceof AInteger ai) {
			return create(value.subtract(new BigDecimal(ai.big())));
		}
		return CVMDouble.create(doubleValue() - b.doubleValue());
	}

	@Override
	public ANumeric multiply(ANumeric b) {
		if (b instanceof CVMBigDecimal bd) {
			return create(value.multiply(bd.value));
		}
		if (b instanceof AInteger ai) {
			return create(value.multiply(new BigDecimal(ai.big())));
		}
		return CVMDouble.create(doubleValue() * b.doubleValue());
	}

	/**
	 * Divides this BigDecimal by another, preserving precision.
	 * Throws ArithmeticException if the result cannot be represented exactly.
	 * 
	 * @param b Divisor
	 * @return Division result
	 */
	public CVMBigDecimal divideExact(CVMBigDecimal b) {
		return create(value.divide(b.value, MathContext.UNLIMITED));
	}

	@Override
	public int compareTo(ANumeric o) {
		if (o instanceof CVMBigDecimal bd) {
			return value.compareTo(bd.value);
		}
		if (o instanceof AInteger) {
			// Compare BigDecimal with integer precisely
			return value.compareTo(new BigDecimal(o.toString()));
		}
		// Fall back to double comparison for CVMDouble
		return Double.compare(doubleValue(), o.doubleValue());
	}

	@Override
	public int estimatedEncodingSize() {
		// Tag + VLQ scale + VLQ byte count + unscaled value bytes
		int unscaledLen = Utils.byteLength(value.unscaledValue());
		return 1 + Format.MAX_VLQ_LONG_LENGTH + Format.getVLQCountLength(unscaledLen) + unscaledLen;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++] = Tag.BIG_DECIMAL;
		return encodeRaw(bs, pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		// Encode scale as VLQ long (signed)
		pos = Format.writeVLQLong(bs, pos, value.scale());
		// Encode unscaled value as VLQ count + raw bytes (same format as BigInteger)
		BigInteger unscaled = value.unscaledValue();
		byte[] unscaledBytes = unscaled.toByteArray();
		pos = Format.writeVLQCount(bs, pos, unscaledBytes.length);
		System.arraycopy(unscaledBytes, 0, bs, pos, unscaledBytes.length);
		pos += unscaledBytes.length;
		return pos;
	}

	@Override
	public final byte getTag() {
		return Tag.BIG_DECIMAL;
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		AString s = Strings.create(toString());
		if (s.count() > limit) {
			sb.append(s.slice(0, limit));
			return false;
		}
		sb.append(s);
		return true;
	}

	@Override
	public String toString() {
		return value.toPlainString();
	}

	@Override
	public AString toCVMString(long limit) {
		if (limit < 1) return null;
		return Strings.create(toString());
	}

	@Override
	public boolean equals(ACell a) {
		if (a == this) return true;
		if (a instanceof CVMBigDecimal bd) {
			return equals(bd);
		}
		return false;
	}

	public boolean equals(CVMBigDecimal a) {
		return value.compareTo(a.value) == 0;
	}

	@Override
	public int hashCode() {
		return value.hashCode();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (value == null) throw new InvalidDataException("Null BigDecimal value");
		BigDecimal stripped = value.stripTrailingZeros();
		BigInteger unscaled = stripped.unscaledValue();
		int byteLen = Utils.byteLength(unscaled);
		if (byteLen > MAX_UNSCALED_BYTELENGTH) {
			throw new InvalidDataException("BigDecimal unscaled value too large: " + byteLen + " bytes");
		}
	}
}
