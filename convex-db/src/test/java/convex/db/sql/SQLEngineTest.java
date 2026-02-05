package convex.db.sql;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.db.lattice.SQLDatabase;

/**
 * Tests for SQLEngine and Calcite integration.
 */
public class SQLEngineTest {

	@Test
	public void testBasicQuery() throws SQLException {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);

		db.tables().createTable("users", new String[]{"id", "name", "email"});
		db.tables().insert("users", 1, "Alice", "alice@example.com");
		db.tables().insert("users", 2, "Bob", "bob@example.com");

		try (SQLEngine engine = SQLEngine.create(db)) {
			List<Object[]> results = engine.query("SELECT * FROM users");
			assertEquals(2, results.size());
		}
	}

	@Test
	public void testSelectColumns() throws SQLException {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);

		db.tables().createTable("products", new String[]{"id", "name", "price"});
		db.tables().insert("products", 1, "Apple", 100);
		db.tables().insert("products", 2, "Banana", 50);

		try (SQLEngine engine = SQLEngine.create(db)) {
			List<Object[]> results = engine.query("SELECT name, price FROM products");
			assertEquals(2, results.size());
			assertEquals(2, results.get(0).length);
		}
	}

	@Test
	public void testWhereClause() throws SQLException {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);

		db.tables().createTable("items", new String[]{"id", "category", "value"});
		db.tables().insert("items", 1, "A", 10);
		db.tables().insert("items", 2, "B", 20);
		db.tables().insert("items", 3, "A", 30);

		try (SQLEngine engine = SQLEngine.create(db)) {
			List<Object[]> results = engine.query("SELECT * FROM items WHERE category = 'A'");
			assertEquals(2, results.size());
		}
	}

	@Test
	public void testCount() throws SQLException {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);

		db.tables().createTable("records", new String[]{"id", "data"});
		for (int i = 1; i <= 5; i++) {
			db.tables().insert("records", i, "data-" + i);
		}

		try (SQLEngine engine = SQLEngine.create(db)) {
			List<Object[]> results = engine.query("SELECT COUNT(*) FROM records");
			assertEquals(1, results.size());
			assertEquals(5L, ((Number) results.get(0)[0]).longValue());
		}
	}

	@Test
	public void testEmptyTable() throws SQLException {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);

		db.tables().createTable("empty_table", new String[]{"id"});

		try (SQLEngine engine = SQLEngine.create(db)) {
			List<Object[]> results = engine.query("SELECT * FROM empty_table");
			assertEquals(0, results.size());
		}
	}

	@Test
	public void testOrderBy() throws SQLException {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);

		db.tables().createTable("sorted", new String[]{"id", "score"});
		db.tables().insert("sorted", 1, 30);
		db.tables().insert("sorted", 2, 10);
		db.tables().insert("sorted", 3, 20);

		try (SQLEngine engine = SQLEngine.create(db)) {
			List<Object[]> results = engine.query("SELECT id, score FROM sorted ORDER BY score");
			assertEquals(3, results.size());
			assertEquals(10L, ((Number) results.get(0)[1]).longValue());
			assertEquals(20L, ((Number) results.get(1)[1]).longValue());
			assertEquals(30L, ((Number) results.get(2)[1]).longValue());
		}
	}
}
