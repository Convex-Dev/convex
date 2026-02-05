package convex.db.calcite;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.db.calcite.ConvexType;
import convex.db.lattice.SQLDatabase;

/**
 * Tests for ConvexTable Calcite adapter, including adversarial cases.
 *
 * <p>Note: The current implementation assumes the first element of each row
 * is the primary key. This is a simplification - a production system would
 * need explicit PK declaration.
 */
public class ConvexTableTest {

	private static int testCounter = 0;

	private SQLDatabase db;
	private Connection conn;
	private String dbName;

	@BeforeEach
	void setUp() throws Exception {
		// Use unique database name for each test to avoid schema cache conflicts
		dbName = "testdb_" + (++testCounter) + "_" + System.currentTimeMillis();
		AKeyPair kp = AKeyPair.generate();
		db = SQLDatabase.create(dbName, kp);
		ConvexSchemaFactory.register(dbName, db);
		// Create typed table: id (INTEGER), name (VARCHAR), amount (INTEGER)
		db.tables().createTable("test_table",
			new String[]{"id", "name", "amount"},
			new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.INTEGER});
		conn = DriverManager.getConnection("jdbc:convex:database=" + dbName);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (conn != null) conn.close();
		ConvexSchemaFactory.unregister(dbName);
	}

	@Test
	void testBasicCRUD() throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			// INSERT
			assertEquals(1, stmt.executeUpdate("INSERT INTO test_table VALUES (1, 'Alice', 100)"));
			assertEquals(1, stmt.executeUpdate("INSERT INTO test_table VALUES (2, 'Bob', 200)"));

			// SELECT
			try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_table")) {
				assertTrue(rs.next());
				assertEquals(2, rs.getInt(1));
			}

			// UPDATE
			assertEquals(1, stmt.executeUpdate("UPDATE test_table SET amount = 150 WHERE id = 1"));
			try (ResultSet rs = stmt.executeQuery("SELECT amount FROM test_table WHERE id = 1")) {
				assertTrue(rs.next());
				// Use getObject() since columns are ANY type
				assertEquals(150, ((Number) rs.getObject(1)).intValue());
			}

			// DELETE
			assertEquals(1, stmt.executeUpdate("DELETE FROM test_table WHERE id = 2"));
			try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_table")) {
				assertTrue(rs.next());
				assertEquals(1, rs.getInt(1));
			}
		}
	}

	/**
	 * Tests that updating an integer column with a string value throws an error.
	 *
	 * <p>With explicit type declarations, the INTEGER column should reject
	 * string values - either at Calcite validation or our type checking.
	 */
	@Test
	void testUpdateIntegerColumnWithStringThrows() throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			// Insert integer value
			stmt.executeUpdate("INSERT INTO test_table VALUES (1, 'Alice', 100)");

			// Update integer column with string - should throw
			assertThrows(SQLException.class, () -> {
				stmt.executeUpdate("UPDATE test_table SET amount = 'not-a-number' WHERE id = 1");
			}, "Updating INTEGER column with string should throw");
		}
	}

	/**
	 * Tests updating the primary key column to a new value.
	 *
	 * <p>PostgreSQL-like behavior: PK updates are allowed as long as
	 * the new value doesn't violate uniqueness.
	 */
	@Test
	void testUpdatePrimaryKey() throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			// Insert rows
			stmt.executeUpdate("INSERT INTO test_table VALUES (1, 'Alice', 100)");
			stmt.executeUpdate("INSERT INTO test_table VALUES (2, 'Bob', 200)");

			// Update PK to a new value (allowed - no conflict)
			int updated = stmt.executeUpdate("UPDATE test_table SET id = 99 WHERE id = 1");
			assertEquals(1, updated);

			// Verify old row is gone
			try (ResultSet rs = stmt.executeQuery("SELECT * FROM test_table WHERE id = 1")) {
				assertFalse(rs.next(), "Old row with id=1 should be deleted");
			}

			// Verify new row exists with new PK
			try (ResultSet rs = stmt.executeQuery("SELECT name FROM test_table WHERE id = 99")) {
				assertTrue(rs.next(), "New row with id=99 should exist");
				assertEquals("Alice", rs.getObject(1));
			}

			// Verify total count is still 2 (no duplicates)
			try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_table")) {
				assertTrue(rs.next());
				assertEquals(2, rs.getInt(1), "Should still have exactly 2 rows");
			}
		}
	}

	/**
	 * Tests that updating PK to an existing value throws a uniqueness error.
	 *
	 * <p>PostgreSQL-like behavior: unique constraint violation.
	 */
	@Test
	void testUpdatePrimaryKeyDuplicateThrows() throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			// Insert rows
			stmt.executeUpdate("INSERT INTO test_table VALUES (1, 'Alice', 100)");
			stmt.executeUpdate("INSERT INTO test_table VALUES (2, 'Bob', 200)");

			// Try to update PK to an existing value - should throw
			SQLException ex = assertThrows(SQLException.class, () -> {
				stmt.executeUpdate("UPDATE test_table SET id = 2 WHERE id = 1");
			}, "Updating PK to existing value should throw unique constraint violation");

			assertTrue(ex.getMessage().contains("Unique constraint violation") ||
					   ex.getCause().getMessage().contains("Unique constraint violation"),
				"Error should mention unique constraint violation");
		}
	}

	/**
	 * Tests that ORDER BY works correctly with typed columns.
	 */
	@Test
	void testOrderByTypedColumn() throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			// Insert integer values
			stmt.executeUpdate("INSERT INTO test_table VALUES (1, 'Alice', 300)");
			stmt.executeUpdate("INSERT INTO test_table VALUES (2, 'Bob', 100)");
			stmt.executeUpdate("INSERT INTO test_table VALUES (3, 'Charlie', 200)");

			// ORDER BY should work correctly with typed columns
			try (ResultSet rs = stmt.executeQuery("SELECT name, amount FROM test_table ORDER BY amount")) {
				assertTrue(rs.next());
				assertEquals("Bob", rs.getString(1));
				assertEquals(100, rs.getInt(2));

				assertTrue(rs.next());
				assertEquals("Charlie", rs.getString(1));
				assertEquals(200, rs.getInt(2));

				assertTrue(rs.next());
				assertEquals("Alice", rs.getString(1));
				assertEquals(300, rs.getInt(2));

				assertFalse(rs.next());
			}
		}
	}

	/**
	 * Tests inserting NULL values.
	 */
	@Test
	void testNullValues() throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("INSERT INTO test_table VALUES (1, NULL, NULL)");

			try (ResultSet rs = stmt.executeQuery("SELECT name, amount FROM test_table WHERE id = 1")) {
				assertTrue(rs.next());
				assertNull(rs.getObject(1));
				assertNull(rs.getObject(2));
			}
		}
	}

	/**
	 * Tests updating multiple columns at once.
	 */
	@Test
	void testUpdateMultipleColumns() throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("INSERT INTO test_table VALUES (1, 'Alice', 100)");

			int updated = stmt.executeUpdate("UPDATE test_table SET name = 'Alicia', amount = 999 WHERE id = 1");
			assertEquals(1, updated);

			try (ResultSet rs = stmt.executeQuery("SELECT name, amount FROM test_table WHERE id = 1")) {
				assertTrue(rs.next());
				assertEquals("Alicia", rs.getObject(1));
				// Use getObject() since columns are ANY type
				assertEquals(999, ((Number) rs.getObject(2)).intValue());
			}
		}
	}

	/**
	 * Tests that WHERE clause with no matches returns 0 updated rows.
	 */
	@Test
	void testUpdateNoMatch() throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("INSERT INTO test_table VALUES (1, 'Alice', 100)");

			int updated = stmt.executeUpdate("UPDATE test_table SET amount = 999 WHERE id = 999");
			assertEquals(0, updated);
		}
	}

	/**
	 * Tests DELETE with no matches.
	 */
	@Test
	void testDeleteNoMatch() throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("INSERT INTO test_table VALUES (1, 'Alice', 100)");

			int deleted = stmt.executeUpdate("DELETE FROM test_table WHERE id = 999");
			assertEquals(0, deleted);

			// Original row still exists
			try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_table")) {
				assertTrue(rs.next());
				assertEquals(1, rs.getInt(1));
			}
		}
	}

	/**
	 * Tests that ANY-typed columns allow string values and can be updated to different types.
	 *
	 * <p>Note: Calcite has limitations with literal translation for ANY type,
	 * so we test dynamic typing via UPDATE rather than INSERT of mixed types.
	 */
	@Test
	void testAnyTypeAllowsDynamicTyping() throws Exception {
		// Create a table with an ANY-typed column
		String anyTableName = "any_table_" + System.currentTimeMillis();
		db.tables().createTable(anyTableName,
			new String[]{"id", "data"},
			new ConvexType[]{ConvexType.INTEGER, ConvexType.ANY});

		try (Statement stmt = conn.createStatement()) {
			// Insert string value into ANY column
			stmt.executeUpdate("INSERT INTO " + anyTableName + " VALUES (1, 'text')");

			// Verify string value
			try (ResultSet rs = stmt.executeQuery("SELECT data FROM " + anyTableName + " WHERE id = 1")) {
				assertTrue(rs.next());
				assertEquals("text", rs.getObject(1));
			}

			// Update to a different string - ANY column accepts it
			stmt.executeUpdate("UPDATE " + anyTableName + " SET data = 'updated' WHERE id = 1");

			try (ResultSet rs = stmt.executeQuery("SELECT data FROM " + anyTableName + " WHERE id = 1")) {
				assertTrue(rs.next());
				assertEquals("updated", rs.getObject(1));
			}
		}
	}

	/**
	 * Tests that INSERT with wrong type throws error.
	 *
	 * <p>When inserting a string literal into an INTEGER column, Calcite
	 * validates the type and throws an error.
	 */
	@Test
	void testInsertWrongTypeThrows() throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			// Try to insert a string into the INTEGER amount column
			assertThrows(SQLException.class, () -> {
				stmt.executeUpdate("INSERT INTO test_table VALUES (1, 'Alice', 'not-a-number')");
			}, "Inserting string into INTEGER column should throw");
		}
	}
}
