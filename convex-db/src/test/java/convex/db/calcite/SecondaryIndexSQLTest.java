package convex.db.calcite;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.db.ConvexDB;
import convex.db.lattice.SQLDatabase;

/**
 * SQL-level integration tests for secondary column indices.
 *
 * <p>Tests the full stack: DDL (CREATE INDEX / DROP INDEX), DML (INSERT/DELETE),
 * and SELECT with WHERE predicates. All results are verified against the
 * golden-source: the same SELECT without the index (full-table-scan path).
 *
 * <p>These tests will be RED until the secondary index feature is implemented.
 */
public class SecondaryIndexSQLTest {

	private static int counter = 0;

	private ConvexDB cdb;
	private SQLDatabase db;
	private Connection conn;
	private String dbName;

	@BeforeEach
	void setUp() throws Exception {
		dbName = "idx_test_" + (++counter) + "_" + System.currentTimeMillis();
		cdb = ConvexDB.create();
		db = cdb.database(dbName);
		cdb.register(dbName);

		db.tables().createTable("employees",
			new String[]{"id", "status", "dept", "score"},
			new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.VARCHAR, ConvexType.INTEGER});

		conn = DriverManager.getConnection("jdbc:convex:database=" + dbName);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (conn != null) conn.close();
		if (cdb != null) cdb.unregister(dbName);
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private void insertRow(Statement s, int id, String status, String dept, int score)
			throws SQLException {
		s.executeUpdate(String.format(
			"INSERT INTO employees VALUES (%d, '%s', '%s', %d)", id, status, dept, score));
	}

	/**
	 * Collects (id, status, dept, score) rows from a ResultSet.
	 */
	private List<String> collectRows(ResultSet rs) throws SQLException {
		List<String> rows = new ArrayList<>();
		while (rs.next()) {
			rows.add(rs.getInt("id") + "|" + rs.getString("status")
				+ "|" + rs.getString("dept") + "|" + rs.getInt("score"));
		}
		rows.sort(String::compareTo);
		return rows;
	}

	// ── DDL ──────────────────────────────────────────────────────────────────

	@Test
	void createIndex_ddl() throws SQLException {
		try (Statement s = conn.createStatement()) {
			// Should execute without error
			s.execute("CREATE INDEX idx_status ON employees (status)");
		}
		assertTrue(db.tables().hasIndex("employees", "status"),
			"Lattice-level index must be created by DDL");
	}

	@Test
	void createIndex_ifNotExists_noError() throws SQLException {
		try (Statement s = conn.createStatement()) {
			s.execute("CREATE INDEX idx_status ON employees (status)");
			// IF NOT EXISTS variant should not throw
			s.execute("CREATE INDEX IF NOT EXISTS idx_status ON employees (status)");
		}
	}

	@Test
	void dropIndex_ddl() throws SQLException {
		try (Statement s = conn.createStatement()) {
			s.execute("CREATE INDEX idx_dept ON employees (dept)");
			s.execute("DROP INDEX idx_dept");
		}
		assertFalse(db.tables().hasIndex("employees", "dept"),
			"Lattice-level index must be removed by DROP INDEX DDL");
	}

	// ── Exact-match WHERE with index ─────────────────────────────────────────

	@Test
	void whereExact_matchesFullScanResult() throws SQLException {
		try (Statement s = conn.createStatement()) {
			s.execute("CREATE INDEX idx_status ON employees (status)");
			insertRow(s, 1, "active",   "eng", 90);
			insertRow(s, 2, "inactive", "eng", 70);
			insertRow(s, 3, "active",   "ops", 80);
			insertRow(s, 4, "pending",  "hr",  60);

			// Index-backed query
			List<String> indexed = collectRows(
				s.executeQuery("SELECT id, status, dept, score FROM employees WHERE status = 'active'"));

			// Golden: drop the index and do a full scan
			s.execute("DROP INDEX idx_status");
			List<String> golden = collectRows(
				s.executeQuery("SELECT id, status, dept, score FROM employees WHERE status = 'active'"));

			assertEquals(golden, indexed, "WHERE exact must match full-scan golden result");
		}
	}

	@Test
	void whereExact_noMatch_emptyResult() throws SQLException {
		try (Statement s = conn.createStatement()) {
			s.execute("CREATE INDEX idx_status ON employees (status)");
			insertRow(s, 10, "active", "eng", 70);
			insertRow(s, 11, "active", "ops", 80);

			ResultSet rs = s.executeQuery(
				"SELECT id FROM employees WHERE status = 'pending'");
			assertFalse(rs.next(), "Query for absent value must return empty result set");
		}
	}

	@Test
	void whereExact_integerColumn() throws SQLException {
		try (Statement s = conn.createStatement()) {
			s.execute("CREATE INDEX idx_score ON employees (score)");
			insertRow(s, 20, "active", "eng", 100);
			insertRow(s, 21, "active", "ops", 100);
			insertRow(s, 22, "active", "hr",   90);

			List<String> indexed = collectRows(
				s.executeQuery("SELECT id, status, dept, score FROM employees WHERE score = 100"));

			s.execute("DROP INDEX idx_score");
			List<String> golden = collectRows(
				s.executeQuery("SELECT id, status, dept, score FROM employees WHERE score = 100"));

			assertEquals(2, indexed.size());
			assertEquals(golden, indexed);
		}
	}

	// ── Range WHERE with index ────────────────────────────────────────────────

	@Test
	void whereRange_integer() throws SQLException {
		try (Statement s = conn.createStatement()) {
			s.execute("CREATE INDEX idx_score ON employees (score)");
			for (int i = 0; i < 10; i++) {
				insertRow(s, 30 + i, "active", "eng", 60 + i * 5); // 60,65,70,...105
			}

			List<String> indexed = collectRows(s.executeQuery(
				"SELECT id, status, dept, score FROM employees WHERE score >= 70 AND score <= 90"));

			s.execute("DROP INDEX idx_score");
			List<String> golden = collectRows(s.executeQuery(
				"SELECT id, status, dept, score FROM employees WHERE score >= 70 AND score <= 90"));

			assertFalse(indexed.isEmpty(), "Range query must return results");
			assertEquals(golden, indexed, "Range result must match full-scan golden");
		}
	}

	// ── DML updates index ─────────────────────────────────────────────────────

	@Test
	void delete_reflectedInIndex() throws SQLException {
		try (Statement s = conn.createStatement()) {
			s.execute("CREATE INDEX idx_dept ON employees (dept)");
			insertRow(s, 40, "active", "eng", 85);
			insertRow(s, 41, "active", "eng", 75);
			insertRow(s, 42, "active", "ops", 70);

			s.executeUpdate("DELETE FROM employees WHERE id = 40");

			List<String> indexed = collectRows(
				s.executeQuery("SELECT id, status, dept, score FROM employees WHERE dept = 'eng'"));

			s.execute("DROP INDEX idx_dept");
			List<String> golden = collectRows(
				s.executeQuery("SELECT id, status, dept, score FROM employees WHERE dept = 'eng'"));

			assertEquals(golden, indexed, "Index must reflect deletion");
		}
	}

	// ── COUNT aggregate with index ────────────────────────────────────────────

	@Test
	void countAggregate_withIndex() throws SQLException {
		try (Statement s = conn.createStatement()) {
			s.execute("CREATE INDEX idx_status ON employees (status)");
			insertRow(s, 50, "active",   "eng", 90);
			insertRow(s, 51, "active",   "ops", 80);
			insertRow(s, 52, "inactive", "hr",  70);

			ResultSet rs = s.executeQuery(
				"SELECT COUNT(*) FROM employees WHERE status = 'active'");
			assertTrue(rs.next());
			assertEquals(2, rs.getInt(1));
		}
	}

	// ── Multiple indices on same table ────────────────────────────────────────

	@Test
	void multipleIndices_onSameTable() throws SQLException {
		try (Statement s = conn.createStatement()) {
			s.execute("CREATE INDEX idx_status ON employees (status)");
			s.execute("CREATE INDEX idx_dept   ON employees (dept)");

			insertRow(s, 60, "active",   "eng", 80);
			insertRow(s, 61, "inactive", "eng", 65);
			insertRow(s, 62, "active",   "ops", 75);

			// Query on status index
			List<String> byStatus = collectRows(
				s.executeQuery("SELECT id, status, dept, score FROM employees WHERE status = 'active'"));
			// Query on dept index
			List<String> byDept = collectRows(
				s.executeQuery("SELECT id, status, dept, score FROM employees WHERE dept = 'eng'"));

			// Verify against scan (drop both indices)
			s.execute("DROP INDEX idx_status");
			s.execute("DROP INDEX idx_dept");

			List<String> goldenStatus = collectRows(
				s.executeQuery("SELECT id, status, dept, score FROM employees WHERE status = 'active'"));
			List<String> goldenDept = collectRows(
				s.executeQuery("SELECT id, status, dept, score FROM employees WHERE dept = 'eng'"));

			assertEquals(goldenStatus, byStatus);
			assertEquals(goldenDept,   byDept);
		}
	}

	// ── Planner uses index: no full-table scan ────────────────────────────────

	@Test
	void plannerUsesIndex_notFullScan() throws SQLException {
		// Insert a large number of rows so that a full scan would touch many blocks.
		// After creating an index and querying for a rare value, the number of rows
		// returned must equal the expected count — but more importantly, this test
		// documents the expectation that the planner chooses the index path.
		// (Verifying the actual plan requires EXPLAIN support — added here as a
		//  placeholder; the assertion falls back to result correctness.)
		try (Statement s = conn.createStatement()) {
			for (int i = 0; i < 500; i++) {
				String st = (i == 250) ? "rare" : "common";
				insertRow(s, i, st, "eng", i);
			}
			s.execute("CREATE INDEX idx_status ON employees (status)");

			List<String> result = collectRows(
				s.executeQuery("SELECT id, status, dept, score FROM employees WHERE status = 'rare'"));

			// Drop index to get golden
			s.execute("DROP INDEX idx_status");
			List<String> golden = collectRows(
				s.executeQuery("SELECT id, status, dept, score FROM employees WHERE status = 'rare'"));

			assertEquals(1, result.size());
			assertEquals(golden, result);
		}
	}
}
