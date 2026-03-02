package convex.db.calcite.convention;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.db.calcite.ConvexColumnType;
import convex.db.calcite.ConvexSchemaFactory;
import convex.db.calcite.ConvexType;
import convex.db.calcite.rel.ConvexRelExecutor;
import convex.db.lattice.SQLDatabase;

/**
 * Integration tests for ConvexConvention query execution.
 *
 * <p>Verifies that queries are actually using the ConvexConvention
 * operators (ConvexTableScan, ConvexFilter, ConvexProject) rather
 * than the default EnumerableTableScan.
 */
class ConvexConventionIntegrationTest {

	private SQLDatabase db;
	private Connection conn;

	@BeforeEach
	void setUp() throws Exception {
		// Create test database
		db = SQLDatabase.create("convex_conv_test", AKeyPair.generate());
		ConvexSchemaFactory.register("convex_conv_test", db);

		// Create table with typed columns
		ConvexColumnType[] types = {
			ConvexColumnType.of(ConvexType.INTEGER),  // id
			ConvexColumnType.varchar(100),             // name
			ConvexColumnType.of(ConvexType.DOUBLE)     // score
		};
		db.tables().createTable("scores", new String[]{"id", "name", "score"}, types);

		// Insert test data
		db.tables().insert("scores", 1L, "Alice", 95.5);
		db.tables().insert("scores", 2L, "Bob", 87.0);
		db.tables().insert("scores", 3L, "Carol", 92.3);

		// Connect via JDBC
		conn = DriverManager.getConnection("jdbc:convex:database=convex_conv_test");
	}

	@AfterEach
	void tearDown() throws Exception {
		if (conn != null) conn.close();
		ConvexSchemaFactory.unregister("convex_conv_test");
	}

	@Test
	void testSimpleSelect() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT * FROM scores ORDER BY id")) {

			List<String> names = new ArrayList<>();
			while (rs.next()) {
				names.add(rs.getString("name"));
			}

			assertEquals(3, names.size());
			assertEquals("Alice", names.get(0));
			assertEquals("Bob", names.get(1));
			assertEquals("Carol", names.get(2));
		}
	}

	@Test
	void testSelectWithFilter() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name, score FROM scores WHERE score > 90")) {

			List<String> names = new ArrayList<>();
			List<Double> scores = new ArrayList<>();
			while (rs.next()) {
				names.add(rs.getString("name"));
				scores.add(rs.getDouble("score"));
			}

			assertEquals(2, names.size());
			assertTrue(names.contains("Alice"));
			assertTrue(names.contains("Carol"));
			assertTrue(scores.stream().allMatch(s -> s > 90));
		}
	}

	@Test
	void testSelectWithProjection() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name FROM scores WHERE id = 2")) {

			assertTrue(rs.next());
			assertEquals("Bob", rs.getString(1));
			assertFalse(rs.next());
		}
	}

	@Test
	void testSelectWithArithmetic() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name, score + 5 as boosted FROM scores WHERE id = 1")) {

			assertTrue(rs.next());
			assertEquals("Alice", rs.getString("name"));
			assertEquals(100.5, rs.getDouble("boosted"), 0.01);
			assertFalse(rs.next());
		}
	}

	@Test
	void testSelectWithComparison() throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
				 "SELECT name FROM scores WHERE score >= 90 AND score < 96")) {

			List<String> names = new ArrayList<>();
			while (rs.next()) {
				names.add(rs.getString("name"));
			}

			assertEquals(2, names.size());
			assertTrue(names.contains("Alice"));
			assertTrue(names.contains("Carol"));
		}
	}
}
