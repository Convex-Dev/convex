package convex.db.psql;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.sql.Timestamp;

import org.junit.jupiter.api.Test;

/** The binary wire format of each type, decoded and encoded by the same codec. */
public class PgBinaryCodecTest {

	@Test
	public void testIntegersRoundTrip() throws SQLException {
		for (long v : new long[] {Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE}) {
			assertEquals(v, PgBinaryCodec.decode(PgBinaryCodec.encode(v, PgType.INT2), PgType.INT2));
		}
		for (long v : new long[] {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE}) {
			assertEquals(v, PgBinaryCodec.decode(PgBinaryCodec.encode(v, PgType.INT4), PgType.INT4));
		}
		for (long v : new long[] {0, 1, 0x7fffffffL, 0x80000000L, 0xffffffffL}) {
			assertEquals(v, PgBinaryCodec.decode(PgBinaryCodec.encode(v, PgType.OID), PgType.OID));
		}
		for (long v : new long[] {Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) {
			assertEquals(v, PgBinaryCodec.decode(PgBinaryCodec.encode(v, PgType.INT8), PgType.INT8));
		}
		assertArrayEquals(new byte[] {0, 0, 0, 0, 0, 0, 1, 2}, PgBinaryCodec.encode(258L, PgType.INT8));
		assertArrayEquals(new byte[] {0, 0, 1, 2}, PgBinaryCodec.encode(258, PgType.INT4));
	}

	@Test
	public void testFloatsBooleansBytesAndText() throws SQLException {
		assertEquals(1.5, PgBinaryCodec.decode(PgBinaryCodec.encode(1.5, PgType.FLOAT8), PgType.FLOAT8));
		assertEquals(-2.25, PgBinaryCodec.decode(PgBinaryCodec.encode(-2.25f, PgType.FLOAT4), PgType.FLOAT4));
		assertEquals(Boolean.TRUE, PgBinaryCodec.decode(PgBinaryCodec.encode(true, PgType.BOOL), PgType.BOOL));
		assertEquals(Boolean.FALSE, PgBinaryCodec.decode(PgBinaryCodec.encode(false, PgType.BOOL), PgType.BOOL));
		byte[] bytes = {1, 2, (byte) 0xff};
		assertArrayEquals(bytes, (byte[]) PgBinaryCodec.decode(PgBinaryCodec.encode(bytes, PgType.BYTEA), PgType.BYTEA));
		assertEquals("héllo", PgBinaryCodec.decode(PgBinaryCodec.encode("héllo", PgType.VARCHAR), PgType.TEXT));
	}

	@Test
	public void testNumericWireFormat() throws SQLException {
		// 12345.6780: digits 1, 2345, 6780 with weight 1 and display scale 4
		assertArrayEquals(numeric(3, 1, 0x0000, 4, 1, 2345, 6780), PgBinaryCodec.encodeNumeric(new BigDecimal("12345.6780")));
		assertArrayEquals(numeric(1, -1, 0x4000, 4, 5), PgBinaryCodec.encodeNumeric(new BigDecimal("-0.0005")));
		assertArrayEquals(numeric(0, 0, 0x0000, 0), PgBinaryCodec.encodeNumeric(BigDecimal.ZERO));
		assertArrayEquals(numeric(0, 0, 0x0000, 2), PgBinaryCodec.encodeNumeric(new BigDecimal("0.00")));
		// 2^64 = 1844 6744 0737 0955 1616
		assertArrayEquals(numeric(5, 4, 0x0000, 0, 1844, 6744, 737, 955, 1616),
			PgBinaryCodec.encodeNumeric(new BigDecimal("18446744073709551616")));
		// 1E+3 has no fraction digits to show
		assertArrayEquals(numeric(1, 0, 0x0000, 0, 1000), PgBinaryCodec.encodeNumeric(new BigDecimal("1E+3")));
		// A trailing zero group is not sent, only counted in the display scale
		assertArrayEquals(numeric(1, 0, 0x0000, 4, 1), PgBinaryCodec.encodeNumeric(new BigDecimal("1.0000")));
	}

	@Test
	public void testNumericRoundTrip() throws SQLException {
		String[] values = {"0", "1", "-1", "0.5", "-123456789.000001", "99999999.9999", "10000", "0.0001",
			"1234567890123456789012345.123456789", "-18446744073709551616", "7.10"};
		for (String s : values) {
			BigDecimal value = new BigDecimal(s);
			BigDecimal back = PgBinaryCodec.decodeNumeric(PgBinaryCodec.encodeNumeric(value));
			assertEquals(0, value.compareTo(back), s);
			assertEquals(Math.max(0, value.scale()), back.scale(), s);
		}
		BigInteger big = BigInteger.TWO.pow(100).negate();
		assertEquals(0, new BigDecimal(big).compareTo((BigDecimal) PgBinaryCodec.decode(PgBinaryCodec.encode(big, PgType.NUMERIC), PgType.NUMERIC)));
		assertEquals(0, BigDecimal.valueOf(42).compareTo((BigDecimal) PgBinaryCodec.decode(PgBinaryCodec.encode(42L, PgType.NUMERIC), PgType.NUMERIC)));
	}

	@Test
	public void testNumericNotFinite() throws SQLException {
		assertArrayEquals(numeric(0, 0, 0xC000, 0), PgBinaryCodec.encode(Double.NaN, PgType.NUMERIC));
		assertArrayEquals(numeric(0, 0, 0xD000, 0), PgBinaryCodec.encode(Double.POSITIVE_INFINITY, PgType.NUMERIC));
		assertArrayEquals(numeric(0, 0, 0xF000, 0), PgBinaryCodec.encode(Double.NEGATIVE_INFINITY, PgType.NUMERIC));
	}

	@Test
	public void testTimestampRoundTrip() throws SQLException {
		Timestamp ts = Timestamp.valueOf("2024-03-01 12:34:56.123456");
		byte[] wire = PgBinaryCodec.encode(ts, PgType.TIMESTAMP);
		assertEquals(ts, PgBinaryCodec.decode(wire, PgType.TIMESTAMP));
		// The same instant given as milliseconds encodes the same, without the microseconds
		Timestamp millis = new Timestamp(ts.getTime());
		assertArrayEquals(PgBinaryCodec.encode(millis, PgType.TIMESTAMPTZ), PgBinaryCodec.encode(ts.getTime(), PgType.TIMESTAMPTZ));
		assertEquals(0L, ByteBuffer.wrap(PgBinaryCodec.encode(PgBinaryCodec.EPOCH_2000_MILLIS, PgType.TIMESTAMP)).getLong());
	}

	@Test
	public void testUnsupportedAndMalformed() {
		assertEquals("0A000", assertThrows(SQLException.class, () -> PgBinaryCodec.encode("{}", PgType.JSON)).getSQLState());
		assertEquals("XX000", assertThrows(SQLException.class, () -> PgBinaryCodec.encode("text", PgType.INT8)).getSQLState());
		assertDoesNotThrow(() -> assertNull(PgBinaryCodec.decode(new byte[] {1, 2}, PgType.JSON)));
		assertEquals("08P01", assertThrows(SQLException.class, () -> PgBinaryCodec.decode(new byte[3], PgType.INT4)).getSQLState());
		assertEquals("08P01", assertThrows(SQLException.class, () -> PgBinaryCodec.decode(new byte[7], PgType.TIMESTAMP)).getSQLState());
		// Digits beyond the display scale that are not zero
		assertEquals("22P02", assertThrows(SQLException.class, () -> PgBinaryCodec.decodeNumeric(numeric(1, -1, 0x0000, 2, 1234))).getSQLState());
	}

	private static byte[] numeric(int ndigits, int weight, int sign, int dscale, int... digits) {
		ByteBuffer buf = ByteBuffer.allocate(8 + 2 * digits.length);
		buf.putShort((short) ndigits).putShort((short) weight).putShort((short) sign).putShort((short) dscale);
		for (int digit : digits) buf.putShort((short) digit);
		return buf.array();
	}
}
