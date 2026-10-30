package convex.db.calcite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.db.ConvexDB;
import convex.db.lattice.RowLWW;
import convex.db.lattice.SQLDatabase;

/**
 * A join whose second table is filtered twice, once on a number and once on
 * text, and whose result is only a constant. With these row counts the planner
 * makes it a semi-join, which returns the left rows alone; the constant is a
 * single INTEGER column.
 */
class JoinTwoFiltersTest {

	private ConvexDB cdb;
	private String dbName;

	@BeforeEach
	void setUp() {
		dbName = "jointwofilters_" + System.nanoTime();
		cdb = ConvexDB.create(RowLWW::merge);
		SQLDatabase db = cdb.database(dbName);
		cdb.register(dbName);
		db.tables().createTable("rel", new String[] { "relid", "fromid", "toid", "sts" },
			new ConvexType[] { ConvexType.INTEGER, ConvexType.INTEGER, ConvexType.INTEGER, ConvexType.INTEGER });
		db.tables().createTable("pow", new String[] { "powid", "relid", "tp", "sts" },
			new ConvexType[] { ConvexType.INTEGER, ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.INTEGER });
		db.tables().insert("rel", 1L, 7L, 8L, 1L);
		db.tables().insert("rel", 5L, 11L, 8L, 1L);
		db.tables().insert("rel", 6L, 11L, 7L, 2L);
		db.tables().insert("pow", 4194306L, 1L, "HIRE", 1L);
		db.tables().insert("pow", 8388610L, 1L, "FIRE", 1L);
		db.tables().insert("pow", 12582914L, 5L, "HIRE", 1L);
		db.tables().insert("pow", 16777218L, 5L, "FIRE", 1L);
		// Enough further rows for the planner to choose a semi-join
		for (long id = 7; id <= 16; id++) db.tables().insert("rel", id, 20L + id, 8L, 2L);
		db.tables().insert("pow", 4194305L, null, "SELECT", 2L);
		db.tables().insert("pow", 8388609L, null, "SELECT", 2L);
		db.tables().insert("pow", 12582913L, null, "RESHARE", 2L);
	}

	@AfterEach
	void tearDown() {
		cdb.unregister(dbName);
	}

	private int count(String sql) throws Exception {
		int n = 0;
		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + dbName);
			 Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) n++;
		}
		return n;
	}

	@Test
	void aNumberAndATextFilterOnTheJoinedTable() throws Exception {
		assertEquals(2, count("SELECT 1 FROM rel r JOIN pow a ON a.relid = r.relid WHERE a.sts = 1 AND a.tp = 'FIRE'"));
	}

	@Test
	void theSameWithAnInList() throws Exception {
		assertEquals(2, count("SELECT 1 FROM rel r JOIN pow a ON a.relid = r.relid WHERE a.sts = 1 AND a.tp IN ('FIRE', '*')"));
	}

	@Test
	void asASemiJoin() throws Exception {
		assertEquals(2, count("SELECT 1 FROM pow a WHERE a.sts = 1 AND a.tp = 'FIRE' AND a.relid IN (SELECT relid FROM rel)"));
	}

	/** The constant is all a semi-join gives back: it must come back as the INTEGER it is. */
	@Test
	void theConstantOfASemiJoinIsAnInteger() throws Exception {
		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + dbName);
			 Statement st = conn.createStatement();
			 ResultSet rs = st.executeQuery("SELECT 1 FROM rel r JOIN pow a ON a.relid = r.relid WHERE a.sts = 1 AND a.tp = 'FIRE'")) {
			int n = 0;
			while (rs.next()) {
				assertEquals(1, rs.getInt(1));
				n++;
			}
			assertEquals(2, n);
		}
	}
}
