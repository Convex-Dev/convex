package convex.db.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import convex.db.ConvexDB;
import convex.db.lattice.RowLWW;

class BinaryLiteralTest {
	private static final byte[] CAFE = {(byte) 0xca, (byte) 0xfe};

	@Test void literalsRoundTripThroughTypedAndUntypedColumns() throws Exception {
		try (var c = ConvexDB.create(RowLWW::merge).getConnection("binary"); var s = c.createStatement()) {
			s.execute("CREATE TABLE t (id INTEGER, v VARBINARY(20), b BINARY(2), a ANY)");
			s.execute("INSERT INTO t VALUES (1, X'CAFE', X'CAFE', X'CAFE')");
			try (var rows = s.executeQuery("SELECT v, b, a FROM t WHERE id = 1")) {
				assertTrue(rows.next());
				for (int i = 1; i <= 2; i++) assertArrayEquals(CAFE, rows.getBytes(i));
				assertArrayEquals(CAFE, assertInstanceOf(byte[].class, rows.getObject(3)));
			}
			s.execute("INSERT INTO t (id, v, a) VALUES (2, X'', X'')");
			try (var rows = s.executeQuery("SELECT v, a, b FROM t WHERE id = 2")) {
				assertTrue(rows.next());
				assertArrayEquals(new byte[0], rows.getBytes(1));
				assertArrayEquals(new byte[0], assertInstanceOf(byte[].class, rows.getObject(2)));
				assertNull(rows.getBytes(3));
				assertTrue(rows.wasNull());
			}
		}
	}

	@Test void literalsWorkInFiltersProjectionsAndUpdates() throws Exception {
		try (var c = ConvexDB.create(RowLWW::merge).getConnection("binary"); var s = c.createStatement()) {
			s.execute("CREATE TABLE t (id INTEGER, v VARBINARY(20))");
			s.execute("INSERT INTO t VALUES (1, X'CAFE'), (2, X'00FF')");
			assertId(c, "SELECT id FROM t WHERE v = X'CAFE'", 1);
			try (var rows = s.executeQuery("SELECT X'CAFE' FROM t WHERE id = 1")) {
				assertTrue(rows.next());
				assertArrayEquals(CAFE, rows.getBytes(1));
			}
			assertEquals(1, s.executeUpdate("UPDATE t SET v = X'0080FF' WHERE v = X'CAFE'"));
			assertId(c, "SELECT id FROM t WHERE v = X'0080FF'", 1);
			assertEquals(1, s.executeUpdate("DELETE FROM t WHERE v = X'00FF'"));
		}
	}

	@Test void byteParametersAndBinaryPrimaryKeysMatchLiterals() throws Exception {
		try (var c = ConvexDB.create(RowLWW::merge).getConnection("binary"); var s = c.createStatement()) {
			s.execute("CREATE TABLE t (k VARBINARY(20), id INTEGER)");
			try (var insert = c.prepareStatement("INSERT INTO t VALUES (?, ?)")) {
				insert.setBytes(1, CAFE);
				insert.setInt(2, 1);
				assertEquals(1, insert.executeUpdate());
			}
			assertId(c, "SELECT id FROM t WHERE k = X'CAFE'", 1);
			s.execute("INSERT INTO t VALUES (X'00FF', 2)");
			try (var query = c.prepareStatement("SELECT id FROM t WHERE k = ?")) {
				query.setBytes(1, new byte[] {0, (byte) 0xff});
				try (var rows = query.executeQuery()) {
					assertTrue(rows.next());
					assertEquals(2, rows.getInt(1));
					assertFalse(rows.next());
				}
			}
		}
	}

	private static void assertId(Connection c, String sql, int expected) throws SQLException {
		try (var s = c.createStatement(); var rows = s.executeQuery(sql)) {
			assertTrue(rows.next(), sql);
			assertEquals(expected, rows.getInt(1), sql);
			assertFalse(rows.next(), sql);
		}
	}
}
