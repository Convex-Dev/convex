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
 * Tests for ConvexSort and ConvexAggregate operators.
 */
class SortAggregateTest {

	private SQLDatabase db;
	private Connection conn;

	@BeforeEach
	void setUp() throws Exception {
		db = SQLDatabase.create("sort_agg_test", AKeyPair.generate());
		ConvexSchemaFactory.register("sort_agg_test", db);

		// Create table with test data
		ConvexColumnType[] types = {
			ConvexColumnType.of(ConvexType.INTEGER),  // id
			ConvexColumnType.varchar(50),              // name
			ConvexColumnType.of(ConvexType.INTEGER),  // category
			ConvexColumnType.of(ConvexType.DOUBLE)    // price
		};
		db.tables().createTable("items", new String[]{"id", "name", "category", "price"}, types);

		// Insert test data
		db.tables().insert("items", 1L, "apple", 1L, 1.50);
		db.tables().insert("items", 2L, "banana", 1L, 0.75);
		db.tables().insert("items", 3L, "carrot", 2L, 0.50);
		db.tables().insert("items", 4L, "donut", 3L, 2.00);
		db.tables().insert("items", 5L, "eggs", 2L, 3.00);

		conn = DriverManager.getConnection("jdbc:convex:database=sort_agg_test");
	}

	@AfterEach
	void tearDown() throws Exception {
		if (conn != null) conn.close();
		ConvexSchemaFactory.unregister("sort_agg_test");
	}

	// ========== Sort Tests ==========

	@Test
	void testOrderByAsc() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name FROM items ORDER BY name")) {
			assertTrue(rs.next());
			assertEquals("apple", rs.getString("name"));
			assertTrue(rs.next());
			assertEquals("banana", rs.getString("name"));
			assertTrue(rs.next());
			assertEquals("carrot", rs.getString("name"));
			assertTrue(rs.next());
			assertEquals("donut", rs.getString("name"));
			assertTrue(rs.next());
			assertEquals("eggs", rs.getString("name"));
			assertFalse(rs.next());
		}
	}

	@Test
	void testOrderByDesc() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name FROM items ORDER BY price DESC")) {
			assertTrue(rs.next());
			assertEquals("eggs", rs.getString("name")); // 3.00
			assertTrue(rs.next());
			assertEquals("donut", rs.getString("name")); // 2.00
			assertTrue(rs.next());
			assertEquals("apple", rs.getString("name")); // 1.50
			assertTrue(rs.next());
			assertEquals("banana", rs.getString("name")); // 0.75
			assertTrue(rs.next());
			assertEquals("carrot", rs.getString("name")); // 0.50
			assertFalse(rs.next());
		}
	}

	@Test
	void testOrderByMultipleColumns() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name FROM items ORDER BY category, price DESC")) {
			assertTrue(rs.next());
			assertEquals("apple", rs.getString("name")); // cat 1, val 1.50
			assertTrue(rs.next());
			assertEquals("banana", rs.getString("name")); // cat 1, val 0.75
			assertTrue(rs.next());
			assertEquals("eggs", rs.getString("name")); // cat 2, val 3.00
			assertTrue(rs.next());
			assertEquals("carrot", rs.getString("name")); // cat 2, val 0.50
			assertTrue(rs.next());
			assertEquals("donut", rs.getString("name")); // cat 3, val 2.00
			assertFalse(rs.next());
		}
	}

	@Test
	void testLimit() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name FROM items ORDER BY id LIMIT 3")) {
			assertTrue(rs.next());
			assertEquals("apple", rs.getString("name"));
			assertTrue(rs.next());
			assertEquals("banana", rs.getString("name"));
			assertTrue(rs.next());
			assertEquals("carrot", rs.getString("name"));
			assertFalse(rs.next());
		}
	}

	@Test
	void testOffset() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name FROM items ORDER BY id LIMIT 2 OFFSET 2")) {
			assertTrue(rs.next());
			assertEquals("carrot", rs.getString("name"));
			assertTrue(rs.next());
			assertEquals("donut", rs.getString("name"));
			assertFalse(rs.next());
		}
	}

	// ========== Aggregate Tests ==========

	@Test
	void testGroupBySum() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT category, SUM(price) as total FROM items GROUP BY category ORDER BY category")) {
			assertTrue(rs.next());
			assertEquals(1, rs.getInt("category"));
			assertEquals(2.25, ((Number) rs.getObject("total")).doubleValue(), 0.01); // 1.50 + 0.75
			assertTrue(rs.next());
			assertEquals(2, rs.getInt("category"));
			assertEquals(3.50, ((Number) rs.getObject("total")).doubleValue(), 0.01); // 0.50 + 3.00
			assertTrue(rs.next());
			assertEquals(3, rs.getInt("category"));
			assertEquals(2.00, ((Number) rs.getObject("total")).doubleValue(), 0.01);
			assertFalse(rs.next());
		}
	}

	@Test
	void testGroupByCount() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT category, COUNT(*) as cnt FROM items GROUP BY category ORDER BY category")) {
			assertTrue(rs.next());
			assertEquals(1, rs.getInt("category"));
			assertEquals(2, rs.getInt("cnt"));
			assertTrue(rs.next());
			assertEquals(2, rs.getInt("category"));
			assertEquals(2, rs.getInt("cnt"));
			assertTrue(rs.next());
			assertEquals(3, rs.getInt("category"));
			assertEquals(1, rs.getInt("cnt"));
			assertFalse(rs.next());
		}
	}

	@Test
	void testGroupByAvg() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT category, AVG(price) as avg_val FROM items GROUP BY category ORDER BY category")) {
			assertTrue(rs.next());
			assertEquals(1, rs.getInt("category"));
			assertEquals(1.125, ((Number) rs.getObject("avg_val")).doubleValue(), 0.01); // (1.50 + 0.75) / 2
			assertTrue(rs.next());
			assertEquals(2, rs.getInt("category"));
			assertEquals(1.75, ((Number) rs.getObject("avg_val")).doubleValue(), 0.01); // (0.50 + 3.00) / 2
			assertTrue(rs.next());
			assertEquals(3, rs.getInt("category"));
			assertEquals(2.00, ((Number) rs.getObject("avg_val")).doubleValue(), 0.01);
			assertFalse(rs.next());
		}
	}

	@Test
	void testGroupByMinMax() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT category, MIN(price) as min_val, MAX(price) as max_val FROM items GROUP BY category ORDER BY category")) {
			assertTrue(rs.next());
			assertEquals(1, rs.getInt("category"));
			assertEquals(0.75, ((Number) rs.getObject("min_val")).doubleValue(), 0.01);
			assertEquals(1.50, ((Number) rs.getObject("max_val")).doubleValue(), 0.01);
			assertTrue(rs.next());
			assertEquals(2, rs.getInt("category"));
			assertEquals(0.50, ((Number) rs.getObject("min_val")).doubleValue(), 0.01);
			assertEquals(3.00, ((Number) rs.getObject("max_val")).doubleValue(), 0.01);
			assertTrue(rs.next());
			assertEquals(3, rs.getInt("category"));
			assertEquals(2.00, ((Number) rs.getObject("min_val")).doubleValue(), 0.01);
			assertEquals(2.00, ((Number) rs.getObject("max_val")).doubleValue(), 0.01);
			assertFalse(rs.next());
		}
	}

	@Test
	void testAggregateWithoutGroupBy() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT COUNT(*), SUM(price), AVG(price), MIN(price), MAX(price) FROM items")) {
			assertTrue(rs.next());
			assertEquals(5, rs.getInt(1));
			assertEquals(7.75, ((Number) rs.getObject(2)).doubleValue(), 0.01);
			assertEquals(1.55, ((Number) rs.getObject(3)).doubleValue(), 0.01);
			assertEquals(0.50, ((Number) rs.getObject(4)).doubleValue(), 0.01);
			assertEquals(3.00, ((Number) rs.getObject(5)).doubleValue(), 0.01);
			assertFalse(rs.next());
		}
	}

	@Test
	void testAggregateWithWhere() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT SUM(price) as total FROM items WHERE category = 1")) {
			assertTrue(rs.next());
			assertEquals(2.25, ((Number) rs.getObject("total")).doubleValue(), 0.01);
			assertFalse(rs.next());
		}
	}

	// ========== Combined Sort + Aggregate Tests ==========

	@Test
	void testGroupByWithOrderBy() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT category, SUM(price) as total FROM items GROUP BY category ORDER BY total DESC")) {
			assertTrue(rs.next());
			assertEquals(2, rs.getInt("category")); // 3.50
			assertTrue(rs.next());
			assertEquals(1, rs.getInt("category")); // 2.25
			assertTrue(rs.next());
			assertEquals(3, rs.getInt("category")); // 2.00
			assertFalse(rs.next());
		}
	}

	@Test
	void testHaving() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT category, COUNT(*) as cnt FROM items GROUP BY category HAVING COUNT(*) > 1 ORDER BY category")) {
			assertTrue(rs.next());
			assertEquals(1, rs.getInt("category"));
			assertTrue(rs.next());
			assertEquals(2, rs.getInt("category"));
			assertFalse(rs.next()); // category 3 has only 1 item
		}
	}
}
