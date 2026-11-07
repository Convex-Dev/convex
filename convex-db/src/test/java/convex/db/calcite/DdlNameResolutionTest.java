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

import convex.db.ConvexDB;
import convex.db.jdbc.IdentifierPolicy;
import convex.db.lattice.RowLWW;
import convex.db.lattice.SQLDatabase;

/**
 * Name resolution in DDL and in an open connection: identifiers the parser
 * upper-cases must still find a lower-case schema and lower-case tables in a
 * database with the case-insensitive identifier policy, and a table created
 * outside the connection must be visible to it.
 */
class DdlNameResolutionTest {

	private static final ConvexType[] TYPES = {ConvexType.INTEGER, ConvexType.VARCHAR};

	private ConvexDB cdb;
	private SQLDatabase db;
	private String dbName;
	private Connection conn;

	@BeforeEach
	void setUp() throws Exception {
		dbName = "lower_case_db_" + System.nanoTime();
		cdb = ConvexDB.create(RowLWW::merge);
		db = cdb.database(dbName);
		cdb.register(dbName);
		IdentifierPolicy.INSENSITIVE.applyTo(db);
		db.tables().createTable("first_table", new String[]{"id", "v"}, TYPES);
		conn = DriverManager.getConnection("jdbc:convex:database=" + dbName);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (conn != null) conn.close();
		cdb.unregister(dbName);
	}

	private long count(String table) throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
			assertTrue(rs.next());
			return rs.getLong(1);
		}
	}

	/** CREATE TABLE qualified with the schema name, which the parser upper-cases. */
	@Test
	void qualifiedCreateFindsTheSchema() throws Exception {
		try (Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("CREATE TABLE " + dbName + ".qualified (id INTEGER, v VARCHAR)");
		}
		assertEquals(0, count("qualified"));
	}

	/** DROP TABLE of a table created through the lattice API with a lower-case name. */
	@Test
	void dropFindsALowerCaseTable() throws Exception {
		assertEquals(0, count("first_table"));
		try (Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("DROP TABLE first_table");
		}
		assertFalse(db.tables().tableExists("first_table"));
	}

	/** CREATE TABLE of a name that already resolves is refused rather than creating a second table. */
	@Test
	void createOfAnExistingTableIsRefused() throws Exception {
		try (Statement stmt = conn.createStatement()) {
			assertThrows(SQLException.class,
				() -> stmt.executeUpdate("CREATE TABLE first_table (id INTEGER, v VARCHAR)"));
			stmt.executeUpdate("CREATE TABLE IF NOT EXISTS first_table (id INTEGER, v VARCHAR)");
		}
		assertArrayEquals(new String[]{"first_table"}, db.tables().getTableNames());
	}

	/** A table created through the lattice API after the connection has used the schema. */
	@Test
	void aTableCreatedLaterIsVisible() throws Exception {
		assertEquals(0, count("first_table"));
		db.tables().createTable("late_table", new String[]{"id", "v"}, TYPES);
		db.tables().insert("late_table", 1L, "one");
		assertEquals(1, count("late_table"));
	}
}
