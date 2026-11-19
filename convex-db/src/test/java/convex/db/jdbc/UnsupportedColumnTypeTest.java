package convex.db.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import convex.db.ConvexDB;
import convex.db.calcite.ConvexType;
import convex.db.lattice.RowLWW;
import convex.db.psql.PgServer;

class UnsupportedColumnTypeTest {
	private static final String[] UNSUPPORTED = {
			"DATE", "TIME", "TIME WITH TIME ZONE", "TIME WITH LOCAL TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE",
			"TIMESTAMP WITH TIME ZONE", "UUID", "INTEGER ARRAY",
			"INTEGER MULTISET", "ROW(x INTEGER)"
	};

	@Test void jdbcRejectsUnsupportedDeclarationsWithoutChangingTheSchema() throws Exception {
		var db = ConvexDB.create(RowLWW::merge);
		try (var c = db.getConnection("types"); var s = c.createStatement()) {
			s.execute("CREATE TABLE keep (id INTEGER, v VARCHAR)");
			s.execute("INSERT INTO keep VALUES (1, 'original')");
			var before = db.value();
			for (String type : UNSUPPORTED) {
				String sql = "CREATE TABLE rejected (id INTEGER, v " + type + ")";
				assertInstanceOf(SQLFeatureNotSupportedException.class, unsupported(() -> s.execute(sql), type));
				assertInstanceOf(SQLFeatureNotSupportedException.class, unsupported(() -> c.prepareStatement(sql), type));
				unsupported(() -> s.execute("CREATE OR REPLACE TABLE keep (id INTEGER, v " + type + ")"), type);
				assertSame(before, db.value(), type);
			}
			try (var rows = s.executeQuery("SELECT v FROM keep")) {
				assertTrue(rows.next()); assertEquals("original", rows.getString(1));
			}
			assertArrayEquals(new String[] {"KEEP"}, db.database("types").tables().getTableNames());
		}
	}

	@Test void failedDeclarationDoesNotDiscardEarlierTransactionWork() throws Exception {
		var db = ConvexDB.create(RowLWW::merge);
		try (var c = db.getConnection("tx"); var s = c.createStatement()) {
			s.execute("CREATE TABLE keep (id INTEGER)");
			c.setAutoCommit(false);
			s.execute("INSERT INTO keep VALUES (1)");
			unsupported(() -> s.execute("CREATE TABLE rejected (id INTEGER, v DATE)"), "DATE");
			c.commit();
			try (var rows = s.executeQuery("SELECT COUNT(*) FROM keep")) {
				assertTrue(rows.next()); assertEquals(1, rows.getLong(1));
			}
			assertArrayEquals(new String[] {"KEEP"}, db.database("tx").tables().getTableNames());
		}
	}

	@Test void explicitlyUntypedAndSupportedDeclarationsStillWork() throws Exception {
		var db = ConvexDB.create(RowLWW::merge);
		try (var c = db.getConnection("supported"); var s = c.createStatement()) {
			s.execute("CREATE TABLE flex_values (id INTEGER, implicit_value, explicit_value ANY)");
			s.execute("INSERT INTO flex_values VALUES (1, 'text', 42)");
			s.execute("INSERT INTO flex_values VALUES (2, TRUE, 'other')");
			try (var rows = s.executeQuery("SELECT COUNT(*) FROM flex_values")) {
				assertTrue(rows.next()); assertEquals(2, rows.getLong(1));
			}
			s.execute("CREATE TABLE supported (id INTEGER, n NUMERIC(10,2), flag BOOLEAN, name VARCHAR(20), "
					+ "stamp TIMESTAMP, raw VARBINARY(20))");
			s.execute("INSERT INTO supported VALUES (1, 12.5, TRUE, 'kept', TIMESTAMP '2026-11-12 10:20:30', NULL)");
			try (var rows = s.executeQuery("SELECT name, stamp, raw FROM supported")) {
				assertTrue(rows.next()); assertEquals("kept", rows.getString(1));
				assertEquals(java.sql.Timestamp.valueOf("2026-11-12 10:20:30"), rows.getTimestamp(2));
				assertNull(rows.getBytes(3));
			}
		}
	}

	@Test void intervalSyntaxRemainsAParserErrorButCannotFallBackToAny() throws Exception {
		var conversion = assertThrows(IllegalArgumentException.class,
				() -> ConvexType.fromSqlTypeName(SqlTypeName.INTERVAL_DAY_SECOND));
		assertInstanceOf(SQLFeatureNotSupportedException.class, conversion.getCause());
		assertEquals("0A000", ((SQLException) conversion.getCause()).getSQLState());
		var db = ConvexDB.create(RowLWW::merge);
		var before = db.value();
		try (var c = db.getConnection("intervals"); var s = c.createStatement()) {
			var error = assertThrows(SQLException.class,
					() -> s.execute("CREATE TABLE rejected (id INTEGER, v INTERVAL DAY TO SECOND)"));
			assertEquals("42601", error.getSQLState());
		}
		assertSame(before, db.value());
	}

	@Test void postgresSimpleAndExtendedQueriesReportUnsupportedTypes() throws Exception {
		var db = ConvexDB.create(RowLWW::merge);
		var server = PgServer.builder().port(0).connectionSupplier(() -> {
			try { return db.getConnection("wire"); }
			catch (SQLException e) { throw new RuntimeException(e); }
		}).build();
		// The wire passes the declaration to JDBC and reports its error: the JDBC test
		// covers every unsupported type, so one shows the wire carries the rejection
		String type = UNSUPPORTED[0];
		String sql = "CREATE TABLE rejected (id INTEGER, v " + type + ")";
		try {
			server.start();
			for (String mode : new String[] {"simple", "extended"}) {
				try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:" + server.getPort()
						+ "/wire?preferQueryMode=" + mode, "test", ""); var s = c.createStatement()) {
					var before = db.value();
					unsupported(() -> s.execute(sql), mode + ": " + type);
					try (var prepared = c.prepareStatement(sql)) {
						unsupported(prepared::execute, mode + " prepared: " + type);
					}
					assertSame(before, db.value(), mode + ": " + type);
					try (var rows = s.executeQuery("SELECT 1")) { assertTrue(rows.next()); }
				}
			}
		} finally {
			server.stop();
		}
	}

	private static SQLException unsupported(Executable action, String description) {
		var error = assertThrows(SQLException.class, action, description);
		assertEquals("0A000", error.getSQLState(), description + ": " + error);
		assertTrue(error.getMessage().contains("Unsupported SQL column type"), error.toString());
		return error;
	}
}
