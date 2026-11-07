package convex.db;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.db.jdbc.IdentifierPolicy;
import convex.db.lattice.RowLWW;
import convex.db.psql.PgServer;

/** Execution-time data errors must carry SQLSTATE through JDBC and both wire query modes (#800). */
public class DataExceptionTest {

	private static final AtomicInteger IDS = new AtomicInteger();
	private record Failure(String sql, String state) {}
	private static final List<Failure> FAILURES = List.of(
		new Failure("SELECT 10 / (id - 1) FROM t", "22012"),
		new Failure("SELECT MOD(10, id - 1) FROM t", "22012"),
		new Failure("SELECT CAST(v AS INTEGER) FROM t", "22P02"),
		new Failure("SELECT n + 1 FROM t", "22003"),
		new Failure("SELECT CAST(n AS INTEGER) FROM t", "22003"),
		new Failure("SELECT 1 / 0", "22012"),
		new Failure("SELECT CAST('uno' AS INTEGER)", "22P02"),
		new Failure("INSERT INTO t VALUES ('abc', 'x', 0)", "22P02")
	);
	private ConvexDB db;
	private String name;
	private PgServer server;

	@BeforeEach
	void setUp() throws SQLException {
		name = "data_errors_" + IDS.incrementAndGet();
		db = ConvexDB.create(RowLWW::merge);
		IdentifierPolicy.LOWER.applyTo(db.database(name));
		db.register(name);
		try (Connection conn = jdbc(); Statement stmt = conn.createStatement()) {
			stmt.execute("CREATE TABLE t (id INTEGER, v VARCHAR, n INTEGER)");
			stmt.execute("INSERT INTO t VALUES (1, 'uno', 9223372036854775807)");
		}
	}

	@AfterEach
	void tearDown() {
		if (server != null) server.stop();
		if (db != null) db.unregister(name);
	}

	private Connection jdbc() throws SQLException {
		return DriverManager.getConnection("jdbc:convex:database=" + name);
	}

	private Connection pgwire(String mode) throws Exception {
		server = PgServer.builder().port(0).database(name).build();
		server.start();
		return DriverManager.getConnection("jdbc:postgresql://localhost:" + server.getPort() + "/" + name
			+ "?user=testuser&socketTimeout=10&preferQueryMode=" + mode);
	}

	@Test
	void jdbcStatements() throws Exception {
		try (Connection conn = jdbc()) { checkFailures(conn, false); }
	}

	@Test
	void jdbcPreparedStatements() throws Exception {
		try (Connection conn = jdbc()) { checkFailures(conn, true); }
	}

	@Test
	void simpleWireQueries() throws Exception {
		try (Connection conn = pgwire("simple")) { checkFailures(conn, false); }
	}

	@Test
	void extendedWireQueries() throws Exception {
		try (Connection conn = pgwire("extended")) { checkFailures(conn, true); }
	}

	@Test
	void jdbcBoundParameters() throws Exception {
		try (Connection conn = jdbc()) { checkParameters(conn); }
	}

	@Test
	void wireBoundParameters() throws Exception {
		try (Connection conn = pgwire("extended")) { checkParameters(conn); }
	}

	private static void checkParameters(Connection conn) throws SQLException {
		try (PreparedStatement stmt = conn.prepareStatement("SELECT 10 / (id - ?) FROM t")) {
			stmt.setLong(1, 1);
			SQLException error = assertThrows(SQLException.class, () -> {
				try (ResultSet rs = stmt.executeQuery()) { while (rs.next()) rs.getObject(1); }
			});
			assertEquals("22012", error.getSQLState());
			stmt.setLong(1, 0);
			try (ResultSet rs = stmt.executeQuery()) {
				assertTrue(rs.next());
				assertEquals(10, rs.getLong(1));
			}
		}
		// Give the parameter a text source type; the outer CAST is the operation under test.
		try (PreparedStatement stmt = conn.prepareStatement("SELECT CAST(CAST(? AS VARCHAR) AS INTEGER)")) {
			stmt.setString(1, "uno");
			SQLException error = assertThrows(SQLException.class, () -> {
				try (ResultSet rs = stmt.executeQuery()) { while (rs.next()) rs.getObject(1); }
			});
			assertEquals("22P02", error.getSQLState());
			stmt.setString(1, "42");
			try (ResultSet rs = stmt.executeQuery()) {
				assertTrue(rs.next());
				assertEquals(42, rs.getInt(1));
			}
		}
	}

	private static void checkFailures(Connection conn, boolean prepared) {
		assertAll(FAILURES.stream().map(failure -> () -> {
			SQLException error = assertThrows(SQLException.class, () -> {
				try (Statement stmt = prepared ? conn.prepareStatement(failure.sql()) : conn.createStatement()) {
					boolean rows = prepared ? ((PreparedStatement) stmt).execute() : stmt.execute(failure.sql());
					if (rows) {
						try (ResultSet rs = stmt.getResultSet()) {
							while (rs.next()) rs.getObject(1);
						}
					}
				}
			}, failure.sql());
			assertEquals(failure.state(), error.getSQLState(), failure.sql());
			// Error recovery must leave the connection usable (including wire Sync).
			try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT 42")) {
				assertTrue(rs.next());
				assertEquals(42, rs.getInt(1));
			}
		}));
		assertDoesNotThrow(() -> {
			try (Statement stmt = conn.createStatement()) {
				try (ResultSet rs = stmt.executeQuery("SELECT CAST(NULL AS INTEGER) / 0")) {
					assertTrue(rs.next());
					assertNull(rs.getObject(1));
				}
				try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM t")) {
					assertTrue(rs.next());
					assertEquals(1, rs.getInt(1), "Failed INSERT must not write a row");
				}
			}
		});
	}
}
