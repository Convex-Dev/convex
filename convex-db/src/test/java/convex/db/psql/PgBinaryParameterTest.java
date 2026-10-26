package convex.db.psql;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** Tests the values handed to JDBC after PostgreSQL binary decoding. */
public class PgBinaryParameterTest {
	@Test
	public void testUnsignedOid() throws SQLException {
		for (long value : new long[] {0, 1, 0x7fffffffL, 0x80000000L, 0xffffffffL}) {
			assertBoundLong(PgType.OID, (int)value, value);
		}
	}

	@Test
	public void testSignedInt4() throws SQLException {
		for (int value : new int[] {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE}) {
			assertBoundLong(PgType.INT4, value, value);
		}
	}

	@Test
	public void testInvalidOidLengths() {
		for (int length : new int[] {0, 1, 3, 5, 8}) {
			AtomicReference<Long> bound=new AtomicReference<>();
			PreparedStatement statement=captureLong(bound);
			SQLException error=assertThrows(SQLException.class,
				() -> PgProtocolHandler.bindBinaryParameter(statement, 7, new byte[length], PgType.OID));
			assertEquals("08P01",error.getSQLState());
			assertNull(bound.get(),"Invalid input must not be bound");
		}
	}

	@Test
	public void testNumeric() throws SQLException {
		// 12345.678: digits 1, 2345, 6780 with weight 1 and display scale 3
		assertEquals(new BigDecimal("12345.6780"), PgProtocolHandler.decodeNumeric(numeric(3, 1, 0x0000, 3, 1, 2345, 6780)));
		assertEquals(new BigDecimal("-0.0005"), PgProtocolHandler.decodeNumeric(numeric(1, -1, 0x4000, 4, 5)));
		assertEquals(0, BigDecimal.ZERO.compareTo(PgProtocolHandler.decodeNumeric(numeric(0, 0, 0x0000, 0))));
		// 2^64 = 1844 6744 0737 0955 1616
		assertEquals(new BigDecimal("18446744073709551616"),
			PgProtocolHandler.decodeNumeric(numeric(5, 4, 0x0000, 0, 1844, 6744, 737, 955, 1616)));
	}

	@Test
	public void testInvalidNumeric() {
		assertEquals("08P01", assertThrows(SQLException.class, () -> PgProtocolHandler.decodeNumeric(new byte[6])).getSQLState());
		byte[] truncated = numeric(2, 0, 0x0000, 0, 1, 2);
		assertEquals("08P01", assertThrows(SQLException.class,
			() -> PgProtocolHandler.decodeNumeric(java.util.Arrays.copyOf(truncated, truncated.length - 2))).getSQLState());
		assertEquals("22P02", assertThrows(SQLException.class, () -> PgProtocolHandler.decodeNumeric(numeric(0, 0, 0xC000, 0))).getSQLState());
		assertEquals("22P02", assertThrows(SQLException.class, () -> PgProtocolHandler.decodeNumeric(numeric(1, 0, 0x0000, 0, 10000))).getSQLState());
	}

	private static byte[] numeric(int ndigits, int weight, int sign, int dscale, int... digits) {
		ByteBuffer buf = ByteBuffer.allocate(8 + 2 * digits.length);
		buf.putShort((short) ndigits).putShort((short) weight).putShort((short) sign).putShort((short) dscale);
		for (int digit : digits) buf.putShort((short) digit);
		return buf.array();
	}

	private static void assertBoundLong(int oid, int wireValue, long expected) throws SQLException {
		AtomicReference<Long> bound=new AtomicReference<>();
		byte[] bytes=ByteBuffer.allocate(4).putInt(wireValue).array();
		PgProtocolHandler.bindBinaryParameter(captureLong(bound), 7, bytes, oid);
		assertEquals(Long.valueOf(expected),bound.get());
	}

	private static PreparedStatement captureLong(AtomicReference<Long> bound) {
		return (PreparedStatement)Proxy.newProxyInstance(PgBinaryParameterTest.class.getClassLoader(),
			new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
				assertEquals("setLong",method.getName());
				assertEquals(7,args[0]);
				bound.set((Long)args[1]);
				return null;
			});
	}
}
