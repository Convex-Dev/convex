package convex.db.calcite.eval;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.db.calcite.ConvexColumnType;
import convex.db.calcite.ConvexSchemaFactory;
import convex.db.calcite.ConvexType;
import convex.db.lattice.SQLDatabase;

/**
 * Tests for numeric expressions and functions in SQL queries.
 *
 * <p>Verifies that arithmetic operations, comparisons, and aggregations
 * work correctly with CVM types throughout the execution pipeline.
 */
class NumericExpressionTest {

	private SQLDatabase db;
	private Connection conn;

	@BeforeEach
	void setUp() throws Exception {
		db = SQLDatabase.create("numeric_test", AKeyPair.generate());
		ConvexSchemaFactory.setDatabase(db);

		// Create table with various numeric types
		ConvexColumnType[] types = {
			ConvexColumnType.of(ConvexType.INTEGER),  // id
			ConvexColumnType.of(ConvexType.INTEGER),  // int_val
			ConvexColumnType.of(ConvexType.DOUBLE),   // double_val
			ConvexColumnType.varchar(50)              // name
		};
		db.tables().createTable("numbers", new String[]{"id", "int_val", "double_val", "name"}, types);

		// Insert test data
		db.tables().insert("numbers", 1L, 10L, 1.5, "alpha");
		db.tables().insert("numbers", 2L, 20L, 2.5, "beta");
		db.tables().insert("numbers", 3L, 30L, 3.5, "gamma");
		db.tables().insert("numbers", 4L, 40L, 4.5, "delta");
		db.tables().insert("numbers", 5L, 50L, 5.5, "epsilon");

		conn = DriverManager.getConnection("jdbc:convex:database=numeric_test");
	}

	@AfterEach
	void tearDown() throws Exception {
		if (conn != null) conn.close();
		ConvexSchemaFactory.setDatabase(null);
	}

	// ========== Arithmetic Operations ==========

	@Test
	void testAddition() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT id, int_val + 5 as added FROM numbers WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals(15, ((Number) rs.getObject("added")).intValue());
		}
	}

	@Test
	void testSubtraction() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT id, int_val - 3 as subtracted FROM numbers WHERE id = 2")) {
			assertTrue(rs.next());
			assertEquals(17, ((Number) rs.getObject("subtracted")).intValue());
		}
	}

	@Test
	void testMultiplication() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT id, int_val * 2 as multiplied FROM numbers WHERE id = 3")) {
			assertTrue(rs.next());
			assertEquals(60, ((Number) rs.getObject("multiplied")).intValue());
		}
	}

	@Test
	void testDivision() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT id, int_val / 2 as divided FROM numbers WHERE id = 4")) {
			assertTrue(rs.next());
			assertEquals(20.0, ((Number) rs.getObject("divided")).doubleValue(), 0.01);
		}
	}

	@Test
	void testModulo() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT id, MOD(int_val, 7) as remainder FROM numbers WHERE id = 5")) {
			assertTrue(rs.next());
			assertEquals(1, ((Number) rs.getObject("remainder")).intValue());
		}
	}

	@Test
	void testNegation() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT id, -int_val as negated FROM numbers WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals(-10, ((Number) rs.getObject("negated")).intValue());
		}
	}

	// ========== Mixed Type Arithmetic ==========

	@Test
	void testIntPlusDouble() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT id, int_val + double_val as mixed FROM numbers WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals(11.5, ((Number) rs.getObject("mixed")).doubleValue(), 0.01);
		}
	}

	@Test
	void testIntTimesDouble() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT id, int_val * double_val as product FROM numbers WHERE id = 2")) {
			assertTrue(rs.next());
			assertEquals(50.0, ((Number) rs.getObject("product")).doubleValue(), 0.01);
		}
	}

	// ========== Complex Expressions ==========

	@Test
	void testComplexExpression() throws Exception {
		// (int_val * 2 + double_val) / 3
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT id, (int_val * 2 + double_val) / 3 as calc FROM numbers WHERE id = 3")) {
			assertTrue(rs.next());
			// (30 * 2 + 3.5) / 3 = 63.5 / 3 = 21.1666...
			assertEquals(21.166, ((Number) rs.getObject("calc")).doubleValue(), 0.01);
		}
	}

	@Test
	void testExpressionInWhere() throws Exception {
		// Find rows where int_val * 2 > 50
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT name FROM numbers WHERE int_val * 2 > 50 ORDER BY id")) {
			assertTrue(rs.next());
			assertEquals("gamma", rs.getString("name")); // 30 * 2 = 60 > 50
			assertTrue(rs.next());
			assertEquals("delta", rs.getString("name")); // 40 * 2 = 80 > 50
			assertTrue(rs.next());
			assertEquals("epsilon", rs.getString("name")); // 50 * 2 = 100 > 50
			assertFalse(rs.next());
		}
	}

	@Test
	void testExpressionComparison() throws Exception {
		// Find rows where int_val + double_val > 25
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT name FROM numbers WHERE int_val + double_val > 25 ORDER BY id")) {
			assertTrue(rs.next());
			assertEquals("gamma", rs.getString("name")); // 30 + 3.5 = 33.5
			assertTrue(rs.next());
			assertEquals("delta", rs.getString("name")); // 40 + 4.5 = 44.5
			assertTrue(rs.next());
			assertEquals("epsilon", rs.getString("name")); // 50 + 5.5 = 55.5
			assertFalse(rs.next());
		}
	}

	// ========== Comparison Operators ==========

	@Test
	void testEquals() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name FROM numbers WHERE int_val = 30")) {
			assertTrue(rs.next());
			assertEquals("gamma", rs.getString("name"));
			assertFalse(rs.next());
		}
	}

	@Test
	void testNotEquals() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM numbers WHERE int_val <> 30")) {
			assertTrue(rs.next());
			assertEquals(4, rs.getInt(1));
		}
	}

	@Test
	void testLessThan() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM numbers WHERE int_val < 25")) {
			assertTrue(rs.next());
			assertEquals(2, rs.getInt(1)); // 10, 20
		}
	}

	@Test
	void testGreaterThan() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM numbers WHERE int_val > 25")) {
			assertTrue(rs.next());
			assertEquals(3, rs.getInt(1)); // 30, 40, 50
		}
	}

	@Test
	void testLessThanOrEqual() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM numbers WHERE int_val <= 30")) {
			assertTrue(rs.next());
			assertEquals(3, rs.getInt(1)); // 10, 20, 30
		}
	}

	@Test
	void testGreaterThanOrEqual() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM numbers WHERE int_val >= 30")) {
			assertTrue(rs.next());
			assertEquals(3, rs.getInt(1)); // 30, 40, 50
		}
	}

	@Test
	void testBetween() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT COUNT(*) FROM numbers WHERE int_val >= 20 AND int_val <= 40")) {
			assertTrue(rs.next());
			assertEquals(3, rs.getInt(1)); // 20, 30, 40
		}
	}

	// ========== Aggregate Functions ==========

	@Test
	void testCount() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM numbers")) {
			assertTrue(rs.next());
			assertEquals(5, rs.getInt(1));
		}
	}

	@Test
	void testSum() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT SUM(int_val) FROM numbers")) {
			assertTrue(rs.next());
			assertEquals(150, ((Number) rs.getObject(1)).intValue()); // 10+20+30+40+50
		}
	}

	@Test
	void testAvg() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT AVG(int_val) FROM numbers")) {
			assertTrue(rs.next());
			assertEquals(30.0, ((Number) rs.getObject(1)).doubleValue(), 0.01);
		}
	}

	@Test
	void testMin() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT MIN(int_val) FROM numbers")) {
			assertTrue(rs.next());
			assertEquals(10, ((Number) rs.getObject(1)).intValue());
		}
	}

	@Test
	void testMax() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT MAX(int_val) FROM numbers")) {
			assertTrue(rs.next());
			assertEquals(50, ((Number) rs.getObject(1)).intValue());
		}
	}

	@Test
	void testSumDouble() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT SUM(double_val) FROM numbers")) {
			assertTrue(rs.next());
			assertEquals(17.5, ((Number) rs.getObject(1)).doubleValue(), 0.01); // 1.5+2.5+3.5+4.5+5.5
		}
	}

	@Test
	void testAvgDouble() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT AVG(double_val) FROM numbers")) {
			assertTrue(rs.next());
			assertEquals(3.5, ((Number) rs.getObject(1)).doubleValue(), 0.01);
		}
	}

	// ========== Aggregate with Expression ==========

	@Test
	void testSumExpression() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT SUM(int_val * 2) FROM numbers")) {
			assertTrue(rs.next());
			assertEquals(300, ((Number) rs.getObject(1)).intValue()); // 2*(10+20+30+40+50)
		}
	}

	@Test
	void testAvgExpression() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT AVG(int_val + double_val) FROM numbers")) {
			assertTrue(rs.next());
			// avg of (11.5, 22.5, 33.5, 44.5, 55.5) = 167.5 / 5 = 33.5
			assertEquals(33.5, ((Number) rs.getObject(1)).doubleValue(), 0.01);
		}
	}

	// ========== Conditional Aggregation ==========

	@Test
	void testSumWithWhere() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT SUM(int_val) FROM numbers WHERE int_val > 20")) {
			assertTrue(rs.next());
			assertEquals(120, ((Number) rs.getObject(1)).intValue()); // 30+40+50
		}
	}

	@Test
	void testCountWithWhere() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM numbers WHERE double_val > 3.0")) {
			assertTrue(rs.next());
			assertEquals(3, rs.getInt(1)); // 3.5, 4.5, 5.5
		}
	}

	// ========== Logical Operators with Numerics ==========

	@Test
	void testAndCondition() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT name FROM numbers WHERE int_val > 15 AND int_val < 45 ORDER BY id")) {
			assertTrue(rs.next());
			assertEquals("beta", rs.getString("name")); // 20
			assertTrue(rs.next());
			assertEquals("gamma", rs.getString("name")); // 30
			assertTrue(rs.next());
			assertEquals("delta", rs.getString("name")); // 40
			assertFalse(rs.next());
		}
	}

	@Test
	void testOrCondition() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT name FROM numbers WHERE int_val = 10 OR int_val = 50 ORDER BY id")) {
			assertTrue(rs.next());
			assertEquals("alpha", rs.getString("name"));
			assertTrue(rs.next());
			assertEquals("epsilon", rs.getString("name"));
			assertFalse(rs.next());
		}
	}

	@Test
	void testNotCondition() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT COUNT(*) FROM numbers WHERE NOT (int_val > 30)")) {
			assertTrue(rs.next());
			assertEquals(3, rs.getInt(1)); // 10, 20, 30
		}
	}

	// ========== Edge Cases ==========

	@Test
	void testDivisionByConstant() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT int_val / 10.0 as tenth FROM numbers WHERE id = 5")) {
			assertTrue(rs.next());
			assertEquals(5.0, ((Number) rs.getObject("tenth")).doubleValue(), 0.01);
		}
	}

	@Test
	void testMultipleAggregates() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT COUNT(*), SUM(int_val), AVG(int_val), MIN(int_val), MAX(int_val) FROM numbers")) {
			assertTrue(rs.next());
			assertEquals(5, rs.getInt(1));
			assertEquals(150, ((Number) rs.getObject(2)).intValue());
			assertEquals(30.0, ((Number) rs.getObject(3)).doubleValue(), 0.01);
			assertEquals(10, ((Number) rs.getObject(4)).intValue());
			assertEquals(50, ((Number) rs.getObject(5)).intValue());
		}
	}
}
