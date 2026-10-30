package convex.db.psql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * PostgreSQL's binary wire format for the types this server exchanges, as one decode and
 * encode pair per type, so that the parameters read and the results written agree with
 * each other and with the type OIDs the server announces.
 *
 * <p>{@link #decode} yields the Java value JDBC binds for the type: {@code Long} for the
 * integer types, {@code Double} for the floating-point types, {@code Boolean},
 * {@code BigDecimal}, {@code String}, {@code byte[]} or {@code Timestamp}; or null for a
 * type with no binary form here, which the caller binds as the bytes it received.
 * {@link #encode} takes the Java value JDBC returned for a column of the type.
 *
 * <p>Malformed input is SQLSTATE 08P01, a numeric parameter that is not a finite number
 * 22P02, and a type with no binary form asked for in binary 0A000.
 */
public final class PgBinaryCodec {

	/** Milliseconds from the Unix epoch to 2000-01-01, the epoch of PostgreSQL's binary timestamps. */
	public static final long EPOCH_2000_MILLIS = 946684800000L;

	private static final int NUMERIC_POSITIVE = 0x0000;
	private static final int NUMERIC_NEGATIVE = 0x4000;
	private static final int NUMERIC_NAN = 0xC000;
	private static final int NUMERIC_POSITIVE_INFINITY = 0xD000;
	private static final int NUMERIC_NEGATIVE_INFINITY = 0xF000;

	private PgBinaryCodec() {}

	/**
	 * Decodes a value received in binary format.
	 *
	 * @param value The bytes received
	 * @param oid The PostgreSQL type OID the client declared for the value
	 * @return The Java value to bind, or null if the type has no binary form here
	 * @throws SQLException if the bytes are malformed for the type
	 */
	public static Object decode(byte[] value, int oid) throws SQLException {
		ByteBuffer buf = ByteBuffer.wrap(value);
		return switch (oid) {
			case PgType.INT2 -> { requireLength(value, 2, "int2"); yield (long) buf.getShort(); }
			case PgType.INT4 -> { requireLength(value, 4, "int4"); yield (long) buf.getInt(); }
			case PgType.OID -> { requireLength(value, 4, "oid"); yield Integer.toUnsignedLong(buf.getInt()); }
			case PgType.INT8 -> { requireLength(value, 8, "int8"); yield buf.getLong(); }
			case PgType.FLOAT4 -> { requireLength(value, 4, "float4"); yield (double) buf.getFloat(); }
			case PgType.FLOAT8 -> { requireLength(value, 8, "float8"); yield buf.getDouble(); }
			case PgType.BOOL -> { requireLength(value, 1, "bool"); yield value[0] != 0; }
			case PgType.NUMERIC -> decodeNumeric(value);
			case PgType.BYTEA -> value;
			case PgType.TEXT, PgType.VARCHAR, PgType.CHAR, PgType.NAME -> new String(value, StandardCharsets.UTF_8);
			case PgType.TIMESTAMP, PgType.TIMESTAMPTZ -> { requireLength(value, 8, "timestamp"); yield decodeTimestamp(buf.getLong()); }
			default -> null;
		};
	}

	/**
	 * Encodes a value to send in binary format.
	 *
	 * @param value The Java value JDBC returned for the column, not null
	 * @param oid The PostgreSQL type OID announced for the column
	 * @return The bytes to send
	 * @throws SQLException with SQLSTATE 0A000 if the type has no binary form here
	 */
	public static byte[] encode(Object value, int oid) throws SQLException {
		return switch (oid) {
			case PgType.INT2 -> ByteBuffer.allocate(2).putShort((short) number(value, oid).longValue()).array();
			case PgType.INT4, PgType.OID -> ByteBuffer.allocate(4).putInt((int) number(value, oid).longValue()).array();
			case PgType.INT8 -> ByteBuffer.allocate(8).putLong(number(value, oid).longValue()).array();
			case PgType.FLOAT4 -> ByteBuffer.allocate(4).putFloat(number(value, oid).floatValue()).array();
			case PgType.FLOAT8 -> ByteBuffer.allocate(8).putDouble(number(value, oid).doubleValue()).array();
			case PgType.BOOL -> new byte[] {(byte) (truth(value, oid) ? 1 : 0)};
			case PgType.NUMERIC -> encodeNumeric(value, oid);
			case PgType.BYTEA -> bytes(value, oid);
			case PgType.TEXT, PgType.VARCHAR, PgType.CHAR, PgType.NAME -> value.toString().getBytes(StandardCharsets.UTF_8);
			case PgType.TIMESTAMP, PgType.TIMESTAMPTZ -> ByteBuffer.allocate(8).putLong(encodeTimestamp(value, oid)).array();
			default -> throw new SQLException("Binary format is not supported for type OID " + oid, "0A000");
		};
	}

	// ========== numeric ==========

	/**
	 * Decodes PostgreSQL's binary numeric: digit count, weight, sign and display scale as
	 * 16-bit integers, then the base 10000 digits, most significant first. The first digit
	 * is worth 10000^weight, and the result has the display scale. NaN and infinities have
	 * no exact value, so are invalid.
	 */
	public static BigDecimal decodeNumeric(byte[] value) throws SQLException {
		if (value.length < 8) {
			throw new SQLException("Binary numeric parameter has " + value.length + " bytes, expected at least 8", "08P01");
		}
		ByteBuffer buf = ByteBuffer.wrap(value);
		int ndigits = buf.getShort();
		int weight = buf.getShort();
		int sign = buf.getShort() & 0xffff;
		int dscale = buf.getShort();
		if (ndigits < 0) throw new SQLException("Binary numeric parameter has a negative digit count", "08P01");
		requireLength(value, 8 + 2 * ndigits, "numeric");
		if ((sign != NUMERIC_POSITIVE && sign != NUMERIC_NEGATIVE) || dscale < 0) {
			throw new SQLException("Binary numeric parameter is not a finite number", "22P02");
		}
		StringBuilder digits = new StringBuilder(4 * ndigits + 1).append('0');
		for (int i = 0; i < ndigits; i++) {
			int digit = buf.getShort();
			if (digit < 0 || digit > 9999) {
				throw new SQLException("Binary numeric parameter has an invalid digit", "22P02");
			}
			String s = Integer.toString(digit);
			digits.append("0000", s.length(), 4).append(s);
		}
		BigDecimal result = new BigDecimal(new BigInteger(digits.toString()))
			.scaleByPowerOfTen(4 * (weight - ndigits + 1));
		// The display scale is the number of fraction digits; any digits beyond it are zeros
		// that filled out the last group
		try {
			result = result.setScale(dscale, RoundingMode.UNNECESSARY);
		} catch (ArithmeticException e) {
			throw new SQLException("Binary numeric parameter has digits beyond its display scale", "22P02");
		}
		return (sign == NUMERIC_NEGATIVE) ? result.negate() : result;
	}

	/**
	 * Encodes a number as PostgreSQL's binary numeric, the inverse of {@link #decodeNumeric}:
	 * the digits of its absolute value in groups of four decimal digits aligned on the
	 * decimal point, without leading or trailing zero groups, the weight of the first group,
	 * the sign, and the number's scale as the display scale. A double that is not finite is
	 * sent as PostgreSQL's NaN or infinity.
	 */
	public static byte[] encodeNumeric(BigDecimal value) {
		BigDecimal abs = value.abs();
		int dscale = Math.max(0, value.scale());
		String plain = (abs.scale() < 0 ? abs.setScale(0) : abs).toPlainString();
		int dot = plain.indexOf('.');
		String intPart = (dot < 0) ? plain : plain.substring(0, dot);
		String fracPart = (dot < 0) ? "" : plain.substring(dot + 1);
		intPart = "0".repeat((4 - intPart.length() % 4) % 4) + intPart;
		fracPart = fracPart + "0".repeat((4 - fracPart.length() % 4) % 4);
		String all = intPart + fracPart;
		int groups = all.length() / 4;
		int[] digits = new int[groups];
		for (int i = 0; i < groups; i++) digits[i] = Integer.parseInt(all, 4 * i, 4 * i + 4, 10);
		int weight = intPart.length() / 4 - 1;
		int start = 0;
		while (start < groups && digits[start] == 0) { start++; weight--; }
		int end = groups;
		while (end > start && digits[end - 1] == 0) end--;
		int ndigits = end - start;
		if (ndigits == 0) weight = 0;
		ByteBuffer buf = ByteBuffer.allocate(8 + 2 * ndigits);
		buf.putShort((short) ndigits).putShort((short) weight)
			.putShort((short) (value.signum() < 0 ? NUMERIC_NEGATIVE : NUMERIC_POSITIVE)).putShort((short) dscale);
		for (int i = start; i < end; i++) buf.putShort((short) digits[i]);
		return buf.array();
	}

	private static byte[] encodeNumeric(Object value, int oid) throws SQLException {
		if (value instanceof BigDecimal d) return encodeNumeric(d);
		if (value instanceof BigInteger i) return encodeNumeric(new BigDecimal(i));
		Number n = number(value, oid);
		if (n instanceof Double || n instanceof Float) {
			double d = n.doubleValue();
			if (Double.isNaN(d)) return nonFiniteNumeric(NUMERIC_NAN);
			if (Double.isInfinite(d)) return nonFiniteNumeric(d > 0 ? NUMERIC_POSITIVE_INFINITY : NUMERIC_NEGATIVE_INFINITY);
			return encodeNumeric(BigDecimal.valueOf(d));
		}
		return encodeNumeric(BigDecimal.valueOf(n.longValue()));
	}

	private static byte[] nonFiniteNumeric(int sign) {
		return ByteBuffer.allocate(8).putShort((short) 0).putShort((short) 0).putShort((short) sign).putShort((short) 0).array();
	}

	// ========== timestamp ==========

	/** A binary timestamp is microseconds from 2000-01-01. */
	private static Timestamp decodeTimestamp(long micros) {
		Timestamp ts = new Timestamp(EPOCH_2000_MILLIS + Math.floorDiv(micros, 1000L));
		ts.setNanos(ts.getNanos() + (int) Math.floorMod(micros, 1000L) * 1000);
		return ts;
	}

	private static long encodeTimestamp(Object value, int oid) throws SQLException {
		if (value instanceof Timestamp ts) {
			return (ts.getTime() - EPOCH_2000_MILLIS) * 1000L + (ts.getNanos() % 1_000_000) / 1000;
		}
		if (value instanceof java.util.Date d) return (d.getTime() - EPOCH_2000_MILLIS) * 1000L;
		return (number(value, oid).longValue() - EPOCH_2000_MILLIS) * 1000L;
	}

	// ========== helpers ==========

	private static Number number(Object value, int oid) throws SQLException {
		if (value instanceof Number n) return n;
		throw mismatch(value, oid);
	}

	private static boolean truth(Object value, int oid) throws SQLException {
		if (value instanceof Boolean b) return b;
		if (value instanceof Number n) return n.intValue() != 0;
		throw mismatch(value, oid);
	}

	private static byte[] bytes(Object value, int oid) throws SQLException {
		if (value instanceof byte[] b) return b;
		throw mismatch(value, oid);
	}

	private static SQLException mismatch(Object value, int oid) {
		return new SQLException("A " + value.getClass().getSimpleName() + " cannot be sent as type OID " + oid, "XX000");
	}

	private static void requireLength(byte[] value, int expected, String type) throws SQLException {
		if (value.length != expected) {
			throw new SQLException("Binary " + type + " parameter has " + value.length + " bytes, expected " + expected, "08P01");
		}
	}
}
