package convex.db.psql;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
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
