package convex.db.calcite.eval;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.db.ConvexDB;
import convex.db.calcite.ConvexColumnType;

import convex.db.calcite.ConvexType;
import convex.db.lattice.SQLDatabase;

/**
 * Tests for JOIN operations and subqueries.
 */
class JoinSubqueryTest {

	private ConvexDB cdb;
	private SQLDatabase db;
	private Connection conn;

	@BeforeEach
	void setUp() throws Exception {
		cdb = ConvexDB.create();
		db = cdb.database("join_test");
		cdb.register("join_test");

		// Create customers table
		ConvexColumnType[] customerTypes = {
			ConvexColumnType.of(ConvexType.INTEGER),  // id
			ConvexColumnType.varchar(50),              // name
			ConvexColumnType.varchar(50)               // city
		};
		db.tables().createTable("customers", new String[]{"id", "name", "city"}, customerTypes);

		// Create orders table
		ConvexColumnType[] orderTypes = {
			ConvexColumnType.of(ConvexType.INTEGER),  // id
			ConvexColumnType.of(ConvexType.INTEGER),  // customer_id
			ConvexColumnType.of(ConvexType.DOUBLE),   // amount
			ConvexColumnType.varchar(20)               // status
		};
		db.tables().createTable("orders", new String[]{"id", "customer_id", "amount", "status"}, orderTypes);

		// Insert test data
		db.tables().insert("customers", 1L, "Alice", "London");
		db.tables().insert("customers", 2L, "Bob", "Paris");
		db.tables().insert("customers", 3L, "Carol", "London");
		db.tables().insert("customers", 4L, "Dave", "Berlin");

		db.tables().insert("orders", 101L, 1L, 100.00, "completed");
		db.tables().insert("orders", 102L, 1L, 200.00, "pending");
		db.tables().insert("orders", 103L, 2L, 150.00, "completed");
		db.tables().insert("orders", 104L, 3L, 300.00, "completed");
		db.tables().insert("orders", 105L, 5L, 50.00, "completed"); // customer_id 5 doesn't exist

		conn = DriverManager.getConnection("jdbc:convex:database=join_test");
	}

	@AfterEach
	void tearDown() throws Exception {
		if (conn != null) conn.close();
		if (cdb != null) cdb.unregister("join_test");
	}

	// ========== INNER JOIN Tests ==========

	@Test
	void testInnerJoin() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT c.name, o.amount FROM customers c " +
				 "INNER JOIN orders o ON c.id = o.customer_id " +
				 "ORDER BY o.amount")) {
			assertTrue(rs.next());
			assertEquals("Alice", rs.getString("name"));
			assertEquals(100.00, rs.getDouble("amount"), 0.01);
			assertTrue(rs.next());
			assertEquals("Bob", rs.getString("name"));
			assertEquals(150.00, rs.getDouble("amount"), 0.01);
			assertTrue(rs.next());
			assertEquals("Alice", rs.getString("name"));
			assertEquals(200.00, rs.getDouble("amount"), 0.01);
			assertTrue(rs.next());
			assertEquals("Carol", rs.getString("name"));
			assertEquals(300.00, rs.getDouble("amount"), 0.01);
			assertFalse(rs.next()); // Order 105 has no matching customer
		}
	}

	@Test
	void testInnerJoinWithWhereOrderBy() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT c.name, o.amount FROM customers c " +
				 "INNER JOIN orders o ON c.id = o.customer_id " +
				 "WHERE o.status = 'completed' ORDER BY c.name")) {
			assertTrue(rs.next());
			assertEquals("Alice", rs.getString("name"));
			assertEquals(100.00, rs.getDouble("amount"), 0.01);
			assertTrue(rs.next());
			assertEquals("Bob", rs.getString("name"));
			assertEquals(150.00, rs.getDouble("amount"), 0.01);
			assertTrue(rs.next());
			assertEquals("Carol", rs.getString("name"));
			assertEquals(300.00, rs.getDouble("amount"), 0.01);
			assertFalse(rs.next());
		}
	}

	@Test
	void testJoinWithAggregate() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT c.name, COUNT(*) as order_count, SUM(o.amount) as total " +
				 "FROM customers c " +
				 "INNER JOIN orders o ON c.id = o.customer_id " +
				 "GROUP BY c.name ORDER BY c.name")) {
			assertTrue(rs.next());
			assertEquals("Alice", rs.getString("name"));
			assertEquals(2, rs.getInt("order_count"));
			assertEquals(300.00, rs.getDouble("total"), 0.01);
			assertTrue(rs.next());
			assertEquals("Bob", rs.getString("name"));
			assertEquals(1, rs.getInt("order_count"));
			assertEquals(150.00, rs.getDouble("total"), 0.01);
			assertTrue(rs.next());
			assertEquals("Carol", rs.getString("name"));
			assertEquals(1, rs.getInt("order_count"));
			assertEquals(300.00, rs.getDouble("total"), 0.01);
			assertFalse(rs.next());
		}
	}

	@Test
	void testInnerJoinWithWhereOrderByDesc() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT c.name, o.amount FROM customers c " +
				 "INNER JOIN orders o ON c.id = o.customer_id " +
				 "WHERE o.status = 'completed' ORDER BY o.amount DESC")) {
			assertTrue(rs.next());
			assertEquals("Carol", rs.getString("name"));
			assertEquals(300.00, rs.getDouble("amount"), 0.01);
			assertTrue(rs.next());
			assertEquals("Bob", rs.getString("name"));
			assertEquals(150.00, rs.getDouble("amount"), 0.01);
			assertTrue(rs.next());
			assertEquals("Alice", rs.getString("name"));
			assertEquals(100.00, rs.getDouble("amount"), 0.01);
			assertFalse(rs.next());
		}
	}

	// ========== LEFT JOIN Tests ==========

	@Test
	void testLeftJoin() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT c.name, o.amount FROM customers c " +
				 "LEFT JOIN orders o ON c.id = o.customer_id " +
				 "ORDER BY c.name, o.amount")) {
			assertTrue(rs.next());
			assertEquals("Alice", rs.getString("name"));
			assertEquals(100.00, rs.getDouble("amount"), 0.01);
			assertTrue(rs.next());
			assertEquals("Alice", rs.getString("name"));
			assertEquals(200.00, rs.getDouble("amount"), 0.01);
			assertTrue(rs.next());
			assertEquals("Bob", rs.getString("name"));
			assertEquals(150.00, rs.getDouble("amount"), 0.01);
			assertTrue(rs.next());
			assertEquals("Carol", rs.getString("name"));
			assertEquals(300.00, rs.getDouble("amount"), 0.01);
			assertTrue(rs.next());
			assertEquals("Dave", rs.getString("name"));
			assertNull(rs.getObject("amount")); // Dave has no orders
			assertFalse(rs.next());
		}
	}

	@Test
	void testLeftJoinFindUnmatched() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT c.name FROM customers c " +
				 "LEFT JOIN orders o ON c.id = o.customer_id " +
				 "WHERE o.id IS NULL")) {
			assertTrue(rs.next());
			assertEquals("Dave", rs.getString("name")); // No orders
			assertFalse(rs.next());
		}
	}

	// ========== Cross Join Tests ==========

	@Test
	void testCrossJoin() throws Exception {
		// Cross join produces cartesian product
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT c.name, o.id as order_id FROM customers c, orders o " +
				 "WHERE c.name = 'Alice' AND o.customer_id = 1 ORDER BY o.id")) {
			assertTrue(rs.next());
			assertEquals("Alice", rs.getString("name"));
			assertEquals(101, ((Number) rs.getObject("order_id")).intValue());
			assertTrue(rs.next());
			assertEquals("Alice", rs.getString("name"));
			assertEquals(102, ((Number) rs.getObject("order_id")).intValue());
			assertFalse(rs.next());
		}
	}

	// ========== Subquery Tests ==========

	@Test
	void testSubqueryInWhere() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT name FROM customers " +
				 "WHERE id IN (SELECT customer_id FROM orders WHERE amount > 100) " +
				 "ORDER BY name")) {
			assertTrue(rs.next());
			assertEquals("Alice", rs.getString("name")); // Has order of 200
			assertTrue(rs.next());
			assertEquals("Bob", rs.getString("name")); // Has order of 150
			assertTrue(rs.next());
			assertEquals("Carol", rs.getString("name")); // Has order of 300
			assertFalse(rs.next());
		}
	}

	@Test
	void testSubqueryInSelect() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT name, " +
				 "  (SELECT COUNT(*) FROM orders WHERE customer_id = customers.id) as order_count " +
				 "FROM customers")) {
			// Collect results (order not guaranteed with correlated subqueries)
			java.util.Map<String, Integer> results = new java.util.HashMap<>();
			while (rs.next()) {
				results.put(rs.getString("name"), rs.getInt("order_count"));
			}
			assertEquals(4, results.size());
			assertEquals(2, results.get("Alice").intValue());
			assertEquals(1, results.get("Bob").intValue());
			assertEquals(1, results.get("Carol").intValue());
			assertEquals(0, results.get("Dave").intValue());
		}
	}

	@Test
	void testSubqueryInFrom() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT city, total_orders FROM " +
				 "  (SELECT c.city, COUNT(*) as total_orders " +
				 "   FROM customers c " +
				 "   INNER JOIN orders o ON c.id = o.customer_id " +
				 "   GROUP BY c.city) sub " +
				 "ORDER BY total_orders DESC")) {
			assertTrue(rs.next());
			assertEquals("London", rs.getString("city"));
			assertEquals(3, rs.getInt("total_orders")); // Alice(2) + Carol(1)
			assertTrue(rs.next());
			assertEquals("Paris", rs.getString("city"));
			assertEquals(1, rs.getInt("total_orders")); // Bob(1)
			assertFalse(rs.next());
		}
	}

	@Test
	void testExistsSubquery() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT name FROM customers c " +
				 "WHERE EXISTS (SELECT 1 FROM orders WHERE customer_id = c.id) " +
				 "ORDER BY name")) {
			assertTrue(rs.next());
			assertEquals("Alice", rs.getString("name"));
			assertTrue(rs.next());
			assertEquals("Bob", rs.getString("name"));
			assertTrue(rs.next());
			assertEquals("Carol", rs.getString("name"));
			assertFalse(rs.next()); // Dave has no orders
		}
	}

	// ========== DISTINCT Tests ==========

	@Test
	void testDistinct() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT DISTINCT city FROM customers ORDER BY city")) {
			assertTrue(rs.next());
			assertEquals("Berlin", rs.getString("city"));
			assertTrue(rs.next());
			assertEquals("London", rs.getString("city"));
			assertTrue(rs.next());
			assertEquals("Paris", rs.getString("city"));
			assertFalse(rs.next());
		}
	}

	@Test
	void testDistinctWithJoin() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT DISTINCT c.city FROM customers c " +
				 "INNER JOIN orders o ON c.id = o.customer_id " +
				 "ORDER BY c.city")) {
			assertTrue(rs.next());
			assertEquals("London", rs.getString("city")); // Alice, Carol
			assertTrue(rs.next());
			assertEquals("Paris", rs.getString("city")); // Bob
			assertFalse(rs.next()); // Berlin (Dave) has no orders
		}
	}
}
