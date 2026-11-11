package convex.db.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;

import org.apache.calcite.jdbc.CalciteConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import convex.db.ConvexDB;
import convex.db.lattice.RowLWW;

class JdbcErrorsTest {
	@Test
	void batchErrorKeepsCompletedCounts() {
		var failure = new java.sql.BatchUpdateException("batch failed", null, 0,
				new long[] {1, 1}, new NumberFormatException("bad number"));
		var translated = assertInstanceOf(java.sql.BatchUpdateException.class, JdbcErrors.translate(failure));
		assertEquals("22P02", translated.getSQLState());
		assertArrayEquals(new long[] {1, 1}, translated.getLargeUpdateCounts());
	}

	@Test
	void prepareExecuteAndFetchErrorsCarrySqlState() throws Exception {
		ConvexDB db = ConvexDB.create(RowLWW::merge);
		try (Connection c = db.getConnection("errors"); Statement s = c.createStatement()) {
			s.execute("CREATE TABLE t (id INTEGER, v VARCHAR)");
			s.execute("INSERT INTO t VALUES (1, 'uno'), (2, '2')");
			assertInstanceOf(SQLSyntaxErrorException.class, state("42601", () -> s.execute("SELEC 1")));
			state("42000", () -> s.execute("SELECT * FROM no_such_table"));
			state("42000", () -> c.prepareStatement("SELECT no_such_column FROM t"));
			state("22012", () -> consume(s, "SELECT 10 / (id - 1) FROM t"));
			state("22012", () -> consume(s, "SELECT 1 / 0"));
			state("22012", () -> consume(s, "SELECT 1 / 0"));
			state("22012", () -> consume(s, "SELECT id FROM t WHERE 10 / (id - 1) > 0"));
			state("22P02", () -> consume(s, "SELECT CAST(v AS INTEGER) FROM t"));
			state("22P02", () -> consume(s, "SELECT id FROM t WHERE CAST(v AS INTEGER) > 0"));
			state("22P02", () -> s.executeUpdate("INSERT INTO t VALUES ('uno', 'bad')"));
			try (PreparedStatement p = c.prepareStatement("SELECT 10 / ? FROM t")) {
				p.setInt(1, 0);
				state("22012", () -> { try (ResultSet r = p.executeQuery()) { while (r.next()) r.getObject(1); } });
				p.setInt(1, 2);
				try (ResultSet r = p.executeQuery()) { assertTrue(r.next()); assertEquals(5, r.getInt(1)); }
			}
			s.execute("CREATE TABLE nums (id INTEGER, v INTEGER)");
			db.database("errors").tables().insert("NUMS", 1L, BigInteger.ONE.shiftLeft(70));
			state("22003", () -> consume(s, "SELECT v FROM nums"));
			state("22003", () -> consume(s, "SELECT id FROM nums WHERE CAST(v AS INTEGER) > 0"));
		}
	}

	@Test
	void existingStatesAndJdbcOwnershipArePreserved() throws Exception {
		try (Connection c = ConvexDB.create(RowLWW::merge).getConnection("ownership"); Statement s = c.createStatement()) {
			assertSame(c, s.getConnection());
			assertSame(c, c.unwrap(CalciteConnection.class));
			assertTrue(c.isWrapperFor(CalciteConnection.class));
			assertSame(c, c.getMetaData().getConnection());
			s.execute("CREATE TABLE t (id INTEGER, v INTEGER)");
			s.execute("INSERT INTO t VALUES (1, 1)");
			state("23000", () -> s.execute("INSERT INTO t VALUES (1, 2)"));
			try (ResultSet r = s.executeQuery("SELECT * FROM t")) {
				assertSame(s, r.getStatement());
				assertSame(r, s.getResultSet());
			}
		}
	}

	private static void consume(Statement s, String sql) throws SQLException {
		try (ResultSet r = s.executeQuery(sql)) {
			while (r.next()) r.getObject(1);
		}
	}

	private static SQLException state(String expected, Executable action) {
		SQLException error = assertThrows(SQLException.class, action);
		assertEquals(expected, error.getSQLState(), error.toString());
		return error;
	}
}
