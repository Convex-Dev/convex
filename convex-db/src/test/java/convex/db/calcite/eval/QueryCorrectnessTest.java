package convex.db.calcite.eval;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.runtime.Hook;
import org.junit.jupiter.api.Test;

import convex.db.ConvexDB;
import convex.db.calcite.rules.ConvexRules;
import convex.db.lattice.RowLWW;

/** Exercises the same SQL values through projection, filter, grouping and Enumerable plans. */
class QueryCorrectnessTest {
	@Test
	void textComparisonsAndExtrema() throws Exception {
		try (Connection c = ConvexDB.create(RowLWW::merge).getConnection("text"); Statement s = c.createStatement()) {
			s.execute("CREATE TABLE t (id INTEGER, v VARCHAR)");
			s.execute("INSERT INTO t VALUES (1, 'b'), (2, 'a'), (3, 'd'), (4, NULL)");
			assertEquals(List.of("[1]", "[3]"), rows(s, "SELECT id FROM t WHERE v > 'a' ORDER BY id"));
			assertEquals(List.of("[2]"), rows(s, "SELECT id FROM t WHERE NOT (v > 'a') ORDER BY id"));
			assertEquals(List.of("[a, d]"), rows(s, "SELECT MIN(v), MAX(v) FROM t"));
			try (PreparedStatement p = c.prepareStatement("SELECT id FROM t WHERE v >= ? ORDER BY id")) {
				p.setString(1, "b");
				assertEquals(List.of("[1]", "[3]"), rows(p.executeQuery()));
			}
			s.execute("INSERT INTO t VALUES (5, 'c')");
			assertEquals(List.of("[a, d]"), rows(s, "SELECT MIN(v), MAX(v) FROM t"));
			assertEquals(List.of("[1]", "[5]"), rows(s, "SELECT id FROM t WHERE v BETWEEN 'b' AND 'c' ORDER BY id"));
			assertEquals(List.of(), rows(s, "SELECT id FROM t WHERE NOT (v = 'b') AND v IS NULL"));
		}
	}

	@Test
	void timestampLiteralsAndRanges() throws Exception {
		try (Connection c = ConvexDB.create(RowLWW::merge).getConnection("timestamps"); Statement s = c.createStatement()) {
			s.execute("CREATE TABLE t (id INTEGER, ts TIMESTAMP(0))");
			s.execute("INSERT INTO t VALUES (1, TIMESTAMP '2025-06-01 00:00:00'),"
					+ "(2, TIMESTAMP '2025-06-02 00:00:00'), (3, TIMESTAMP '2025-04-01 00:00:00'),"
					+ "(4, TIMESTAMP '2025-08-01 00:00:00'), (5, NULL)");
			assertEquals(List.of("[1]"), rows(s, "SELECT id FROM t WHERE ts = TIMESTAMP '2025-06-01 00:00:00'"));
			assertEquals(List.of("[1]", "[2]"), rows(s, "SELECT id FROM t WHERE ts BETWEEN TIMESTAMP '2025-05-01 00:00:00'"
					+ " AND TIMESTAMP '2025-07-01 00:00:00' ORDER BY id"));
			assertEquals(List.of("[2]", "[4]"), rows(s, "SELECT id FROM t WHERE ts > TIMESTAMP '2025-06-01 00:00:00' ORDER BY id"));
			try (PreparedStatement p = c.prepareStatement("SELECT id FROM t WHERE ts >= ? ORDER BY id")) {
				p.setTimestamp(1, java.sql.Timestamp.valueOf("2025-06-01 00:00:00"));
				assertEquals(List.of("[1]", "[2]", "[4]"), rows(p.executeQuery()));
			}
		}
	}

	@Test
	void integerDivisionAcrossExpressions() throws Exception {
		try (Connection c = ConvexDB.create(RowLWW::merge).getConnection("division"); Statement s = c.createStatement()) {
			s.execute("CREATE TABLE t (id INTEGER, n INTEGER)");
			s.execute("INSERT INTO t VALUES (1, 7), (2, 8), (3, 9), (4, 10), (5, -7), (6, NULL)");
			assertEquals(List.of("[1]"), rows(s, "SELECT id FROM t WHERE n / 2 = 3"));
			assertEquals(List.of("[2]", "[3]"), rows(s, "SELECT id FROM t WHERE n / 2 = 4 ORDER BY id"));
			assertEquals(List.of("[5]"), rows(s, "SELECT id FROM t WHERE n / 2 = -3"));
			assertEquals(List.of("[-3, 1]", "[3, 1]", "[4, 2]", "[5, 1]"),
					rows(s, "SELECT n / 2, COUNT(*) FROM t WHERE n IS NOT NULL GROUP BY n / 2 ORDER BY 1"));
			assertEquals(List.of("[1]"), rows(s, "SELECT id FROM t WHERE n / 2.0 = 3.5"));
			assertEquals(List.of("[6]"), rows(s, "SELECT id FROM t WHERE n / 2 IS NULL"));
		}
	}

	@Test
	void enumerableSumAndAverageKeepWidening() throws Exception {
		// Force the alternate aggregate implementation, independent of cost estimates.
		try (Hook.Closeable hook = Hook.PLANNER.addThread((RelOptPlanner planner) -> planner.removeRule(ConvexRules.AGGREGATE));
			 Connection c = ConvexDB.create(RowLWW::merge).getConnection("aggregate"); Statement s = c.createStatement()) {
			s.execute("CREATE TABLE d (id INTEGER, v VARCHAR)");
			s.execute("INSERT INTO d VALUES (1, 'one'), (2, 'two'), (3, 'three')");
			s.execute("CREATE TABLE f (id INTEGER, did INTEGER, amt INTEGER)");
			try (PreparedStatement p = c.prepareStatement("INSERT INTO f VALUES (?, ?, ?)")) {
				for (int i = 1; i <= 300; i++) {
					p.setInt(1, i); p.setInt(2, i % 3 + 1); p.setLong(3, Long.MAX_VALUE);
					p.executeUpdate();
				}
			}
			String sql = "SELECT d.id, SUM(f.amt), AVG(f.amt) FROM f JOIN d ON f.did = d.id GROUP BY d.id ORDER BY d.id";
			assertTrue(rows(s, "EXPLAIN PLAN FOR " + sql).toString().contains("EnumerableAggregate"));
			try (ResultSet r = s.executeQuery(sql)) {
				for (int i = 1; i <= 3; i++) {
					assertTrue(r.next());
					assertEquals(i, r.getInt(1));
					assertEquals(new BigDecimal(Long.MAX_VALUE).multiply(BigDecimal.valueOf(100)).stripTrailingZeros(), r.getBigDecimal(2).stripTrailingZeros());
					// Exact AVG across plans is separate column-codec work (#793); this
					// alternate plan must execute, with an exact widened SUM.
					assertEquals((double) Long.MAX_VALUE, r.getDouble(3));
				}
				assertFalse(r.next());
			}
			assertEquals(List.of("[null, null]"), rows(s, "SELECT SUM(amt), AVG(amt) FROM f WHERE id < 0"));
		}
	}

	@Test
	void dateLiteralsMatchLegacyUntypedColumns() throws Exception {
		// Version 0 DDL records DATE as ANY (#793), with Calcite's epoch-day values.
		try (Connection c = ConvexDB.create(RowLWW::merge).getConnection("dates"); Statement s = c.createStatement()) {
			s.execute("CREATE TABLE t (id INTEGER, d DATE)");
			s.execute("INSERT INTO t VALUES (1, DATE '2025-06-01'), (2, DATE '2025-06-02'), (3, DATE '2025-06-03')");
			assertEquals(List.of("[1]"), rows(s, "SELECT id FROM t WHERE d = DATE '2025-06-01'"));
			assertEquals(List.of("[2]", "[3]"), rows(s, "SELECT id FROM t WHERE d >= DATE '2025-06-02' ORDER BY id"));
			assertEquals(List.of("[1]", "[2]"), rows(s, "SELECT id FROM t WHERE d BETWEEN DATE '2025-06-01' AND DATE '2025-06-02' ORDER BY id"));
		}
	}

	private static List<String> rows(Statement s, String sql) throws Exception {
		return rows(s.executeQuery(sql));
	}

	private static List<String> rows(ResultSet result) throws Exception {
		try (result) {
			List<String> all = new ArrayList<>();
			while (result.next()) {
				List<Object> row = new ArrayList<>();
				for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) row.add(result.getObject(i));
				all.add(row.toString());
			}
			return all;
		}
	}
}
