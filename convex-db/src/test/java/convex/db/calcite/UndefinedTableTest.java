package convex.db.calcite;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import convex.db.ConvexDB;
import convex.db.lattice.RowLWW;

/**
 * A statement naming a table that does not exist fails with SQLSTATE 42P01
 * (undefined_table), whether it reads, writes or drops it, and whether or not the
 * planner can suggest another name. Other validation errors keep their own codes.
 */
class UndefinedTableTest {

	private Connection conn;
	private Statement stmt;

	@BeforeEach
	void setUp() throws Exception {
		ConvexDB cdb = ConvexDB.create(RowLWW::merge);
		conn = cdb.getConnection("undefined");
		stmt = conn.createStatement();
		stmt.executeUpdate("CREATE TABLE existing (id INTEGER, v VARCHAR)");
	}

	@AfterEach
	void tearDown() throws Exception {
		conn.close();
	}

	/** The first SQLSTATE in the cause chain, where Convex DB places its codes. */
	private static String sqlState(Executable statement) {
		SQLException e = assertThrows(SQLException.class, statement);
		for (Throwable c = e; c != null; c = c.getCause()) {
			if (c instanceof SQLException s && s.getSQLState() != null) return s.getSQLState();
		}
		return null;
	}

	@Test
	void select() {
		assertEquals("42P01", sqlState(() -> stmt.executeQuery("SELECT * FROM missing")));
	}

	@Test
	void selectWithASuggestion() {
		assertEquals("42P01", sqlState(() -> stmt.executeQuery("SELECT * FROM \"existing\"")));
	}

	@Test
	void qualifiedBySchema() {
		assertEquals("42P01", sqlState(() -> stmt.executeQuery("SELECT * FROM \"undefined\".missing")));
	}

	@Test
	void joined() {
		assertEquals("42P01", sqlState(() -> stmt.executeQuery("SELECT * FROM existing JOIN missing ON TRUE")));
	}

	@Test
	void prepared() {
		assertEquals("42P01", sqlState(() -> conn.prepareStatement("SELECT * FROM missing WHERE id = ?")));
	}

	@Test
	void insert() {
		assertEquals("42P01", sqlState(() -> stmt.executeUpdate("INSERT INTO missing VALUES (1, 'a')")));
	}

	@Test
	void drop() {
		assertEquals("42P01", sqlState(() -> stmt.executeUpdate("DROP TABLE missing")));
	}

	/** A missing column is not a missing table. */
	@Test
	void missingColumnIsNotUndefinedTable() {
		assertNotEquals("42P01", sqlState(() -> stmt.executeQuery("SELECT nothing FROM existing")));
	}
}
