package convex.db.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.Keyword;
import convex.db.ConvexDB;
import convex.db.calcite.ConvexType;
import convex.db.lattice.RowLWW;
import convex.db.lattice.SQLDatabase;

/**
 * The identifier policy a database's {@code :config} section sets for SQL: how unquoted
 * names fold, and whether names compare exactly. Without the setting, a database
 * follows the SQL standard.
 */
class IdentifierPolicyTest {

	private static final Keyword IDENTIFIERS = Keyword.intern("identifiers");
	private static final ConvexType[] TYPES = {ConvexType.INTEGER};

	private ConvexDB cdb;
	private SQLDatabase db;

	@BeforeEach
	void setUp() {
		cdb = ConvexDB.create(RowLWW::merge);
		db = cdb.database("idents");
		db.tables().createTable("lower_table", new String[]{"id"}, TYPES);
		db.tables().createTable("UPPER_TABLE", new String[]{"id"}, TYPES);
	}

	private void identifiers(String policy) {
		db.configure(IDENTIFIERS, Keyword.intern(policy));
	}

	/** Whether a query naming the table as written resolves on a new connection. */
	private boolean resolves(String table) throws SQLException {
		try (Connection conn = cdb.getConnection("idents");
				Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
			return rs.next();
		} catch (SQLException e) {
			return false;
		}
	}

	private void execute(String sql) throws SQLException {
		try (Connection conn = cdb.getConnection("idents"); Statement stmt = conn.createStatement()) {
			stmt.executeUpdate(sql);
		}
	}

	private boolean stored(String name) {
		return db.tables().tableExists(name);
	}

	/** The standard: unquoted names fold to upper case, and every name compares exactly. */
	@Test
	void standardByDefault() throws Exception {
		assertNull(db.getSetting(IDENTIFIERS));
		assertTrue(resolves("upper_table"));
		assertTrue(resolves("\"UPPER_TABLE\""));
		assertTrue(resolves("\"lower_table\""));
		assertFalse(resolves("lower_table"));
		assertFalse(resolves("\"upper_table\""));

		execute("CREATE TABLE Mixed (id INTEGER)");
		assertTrue(stored("MIXED"));
		execute("CREATE TABLE \"Quoted\" (id INTEGER)");
		assertTrue(stored("Quoted"));
		assertFalse(resolves("quoted"));
	}

	/** {@code :upper} is the standard, set explicitly. */
	@Test
	void upper() throws Exception {
		identifiers("upper");
		assertTrue(resolves("upper_table"));
		assertFalse(resolves("lower_table"));
	}

	/** {@code :lower} folds unquoted names to lower case and compares exactly. */
	@Test
	void lower() throws Exception {
		identifiers("lower");
		assertTrue(resolves("LOWER_TABLE"));
		assertTrue(resolves("\"UPPER_TABLE\""));
		assertFalse(resolves("upper_table"));
		assertFalse(resolves("\"LOWER_TABLE\""));

		execute("CREATE TABLE Mixed (id INTEGER)");
		assertTrue(stored("mixed"));
	}

	/** {@code :insensitive} folds unquoted names to upper case and matches names in any case. */
	@Test
	void insensitive() throws Exception {
		identifiers("insensitive");
		assertTrue(resolves("lower_table"));
		assertTrue(resolves("\"LOWER_TABLE\""));
		assertTrue(resolves("Upper_Table"));

		execute("CREATE TABLE Mixed (id INTEGER)");
		assertTrue(stored("MIXED"));
		assertThrows(SQLException.class, () -> execute("CREATE TABLE mixed (id INTEGER)"));
	}

	/** A connection applies the policy the database holds when it opens. */
	@Test
	void appliedWhenAConnectionOpens() throws Exception {
		try (Connection conn = cdb.getConnection("idents"); Statement stmt = conn.createStatement()) {
			identifiers("lower");
			assertThrows(SQLException.class, () -> stmt.executeQuery("SELECT COUNT(*) FROM LOWER_TABLE"));
		}
		assertTrue(resolves("LOWER_TABLE"));
	}

	/** A database whose policy this implementation does not know cannot be opened for SQL. */
	@Test
	void unknownPolicyRefused() {
		identifiers("klingon");
		SQLException e = assertThrows(SQLException.class, () -> cdb.getConnection("idents"));
		assertEquals("0A000", e.getSQLState());
		db.configure(IDENTIFIERS, null);
		assertNull(db.getSetting(IDENTIFIERS));
	}

	/** Settings this implementation does not use are kept alongside its own. */
	@Test
	void otherSettingsKept() throws Exception {
		Keyword other = Keyword.intern("other-setting");
		db.configure(other, Keyword.intern("anything"));
		identifiers("lower");
		assertEquals(Keyword.intern("anything"), db.getSetting(other));
		assertEquals(Keyword.intern("lower"), db.getSetting(IDENTIFIERS));
		assertTrue(resolves("LOWER_TABLE"));
		assertTrue(db.tables().tableExists("lower_table"), "configuring leaves the tables alone");
	}
}
