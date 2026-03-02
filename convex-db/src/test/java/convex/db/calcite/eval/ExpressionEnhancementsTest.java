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
 * Tests for enhanced expression evaluator features:
 * CASE, COALESCE, CAST, math functions, string functions.
 */
class ExpressionEnhancementsTest {

	private SQLDatabase db;
	private Connection conn;

	@BeforeEach
	void setUp() throws Exception {
		db = SQLDatabase.create("expr_test", AKeyPair.generate());
		ConvexSchemaFactory.register("expr_test", db);

		ConvexColumnType[] types = {
			ConvexColumnType.of(ConvexType.INTEGER),  // id
			ConvexColumnType.varchar(50),              // name
			ConvexColumnType.of(ConvexType.INTEGER),  // score
			ConvexColumnType.of(ConvexType.DOUBLE),   // rating
			ConvexColumnType.varchar(50)               // code
		};
		db.tables().createTable("data", new String[]{"id", "name", "score", "rating", "code"}, types);

		db.tables().insert("data", 1L, "Alice", 85L, 4.5, "A001");
		db.tables().insert("data", 2L, "Bob", null, 3.2, "B002");
		db.tables().insert("data", 3L, "Carol", 92L, null, null);
		db.tables().insert("data", 4L, "Dave", 78L, 4.0, "D004");
		db.tables().insert("data", 5L, "Eve", 95L, 4.8, "E005");

		conn = DriverManager.getConnection("jdbc:convex:database=expr_test");
	}

	@AfterEach
	void tearDown() throws Exception {
		if (conn != null) conn.close();
		ConvexSchemaFactory.unregister("expr_test");
	}

	// ========== COALESCE Tests ==========

	@Test
	void testCoalesce() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name, COALESCE(score, 0) as s FROM data WHERE id = 2")) {
			assertTrue(rs.next());
			assertEquals("Bob", rs.getString("name"));
			assertEquals(0, ((Number) rs.getObject("s")).intValue());
		}
	}

	@Test
	void testCoalesceNonNull() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name, COALESCE(score, 0) as s FROM data WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals("Alice", rs.getString("name"));
			assertEquals(85, ((Number) rs.getObject("s")).intValue());
		}
	}

	@Test
	void testCoalesceMultipleArgs() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name, COALESCE(code, name, 'unknown') as res FROM data WHERE id = 3")) {
			assertTrue(rs.next());
			assertEquals("Carol", rs.getString("res")); // code is null, so use name
		}
	}

	// ========== CASE Tests ==========

	@Test
	void testCaseWhen() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT name, CASE WHEN score >= 90 THEN 'A' WHEN score >= 80 THEN 'B' ELSE 'C' END as grade " +
				 "FROM data WHERE score IS NOT NULL ORDER BY id")) {
			assertTrue(rs.next());
			assertEquals("Alice", rs.getString("name"));
			assertEquals("B", rs.getString("grade")); // 85
			assertTrue(rs.next());
			assertEquals("Carol", rs.getString("name"));
			assertEquals("A", rs.getString("grade")); // 92
			assertTrue(rs.next());
			assertEquals("Dave", rs.getString("name"));
			assertEquals("C", rs.getString("grade")); // 78
			assertTrue(rs.next());
			assertEquals("Eve", rs.getString("name"));
			assertEquals("A", rs.getString("grade")); // 95
		}
	}

	// ========== CAST Tests ==========

	@Test
	void testCastToInteger() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name, CAST(rating AS INTEGER) as r FROM data WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals(4, ((Number) rs.getObject("r")).intValue()); // 4.5 -> 4
		}
	}

	@Test
	void testCastToDouble() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name, CAST(score AS DOUBLE) as s FROM data WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals(85.0, ((Number) rs.getObject("s")).doubleValue(), 0.01);
		}
	}

	@Test
	void testCastToVarchar() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT CAST(score AS VARCHAR) as s FROM data WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals("85", rs.getString("s"));
		}
	}

	// ========== Math Function Tests ==========

	@Test
	void testAbs() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT ABS(-5) as a FROM data WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals(5, ((Number) rs.getObject("a")).intValue());
		}
	}

	@Test
	void testFloor() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT FLOOR(rating) as f FROM data WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals(4.0, ((Number) rs.getObject("f")).doubleValue(), 0.01);
		}
	}

	@Test
	void testCeil() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT CEIL(rating) as c FROM data WHERE id = 2")) {
			assertTrue(rs.next());
			assertEquals(4.0, ((Number) rs.getObject("c")).doubleValue(), 0.01); // 3.2 -> 4
		}
	}

	@Test
	void testSqrt() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT SQRT(16) as s FROM data WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals(4.0, ((Number) rs.getObject("s")).doubleValue(), 0.01);
		}
	}

	@Test
	void testPower() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT POWER(2, 3) as p FROM data WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals(8.0, ((Number) rs.getObject("p")).doubleValue(), 0.01);
		}
	}

	// ========== String Function Tests ==========

	@Test
	void testUpper() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT UPPER(name) as u FROM data WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals("ALICE", rs.getString("u"));
		}
	}

	@Test
	void testLower() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT LOWER(name) as l FROM data WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals("alice", rs.getString("l"));
		}
	}

	@Test
	void testSubstring() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT SUBSTRING(name, 1, 3) as s FROM data WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals("Ali", rs.getString("s"));
		}
	}

	@Test
	void testCharLength() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT CHAR_LENGTH(name) as len FROM data WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals(5, ((Number) rs.getObject("len")).intValue()); // "Alice" = 5
		}
	}

	@Test
	void testConcat() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name || '-' || code as combined FROM data WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals("Alice-A001", rs.getString("combined"));
		}
	}

	// ========== Complex Expression Tests ==========

	@Test
	void testNestedCase() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT name, " +
				 "  CASE " +
				 "    WHEN score IS NULL THEN 'No Score' " +
				 "    WHEN score >= 90 THEN 'Excellent' " +
				 "    WHEN score >= 80 THEN 'Good' " +
				 "    ELSE 'Average' " +
				 "  END as status " +
				 "FROM data ORDER BY id")) {
			assertTrue(rs.next());
			assertEquals("Good", rs.getString("status").trim()); // Alice: 85
			assertTrue(rs.next());
			assertEquals("No Score", rs.getString("status").trim()); // Bob: null
			assertTrue(rs.next());
			assertEquals("Excellent", rs.getString("status").trim()); // Carol: 92
			assertTrue(rs.next());
			assertEquals("Average", rs.getString("status").trim()); // Dave: 78
			assertTrue(rs.next());
			assertEquals("Excellent", rs.getString("status").trim()); // Eve: 95
		}
	}

	@Test
	void testCombinedFunctions() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT UPPER(name) as n, COALESCE(CAST(score AS VARCHAR), 'N/A') as s " +
				 "FROM data WHERE id IN (1, 2) ORDER BY id")) {
			assertTrue(rs.next());
			assertEquals("ALICE", rs.getString("n"));
			assertEquals("85", rs.getString("s"));
			assertTrue(rs.next());
			assertEquals("BOB", rs.getString("n"));
			assertEquals("N/A", rs.getString("s"));
		}
	}
}
