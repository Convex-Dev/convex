package convex.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.calcite.ConvexType;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLSchema;
import convex.etch.EtchStore;
import convex.node.NodeServer;

/**
 * End-to-end persistence tests for Convex DB.
 *
 * <p>Verifies that data inserted via JDBC and lattice API survives
 * a full persist → close → reopen cycle on EtchStore.
 *
 * <p>Targets issue #541: "Persist does not scale".
 */
public class ConvexDBPersistenceTest {

	private static final String DB_NAME = "persist_test";
	private static final String TABLE_NAME = "t";

	private NodeServer<?> server;
	private EtchStore store;
	private File etchFile;

	@AfterEach
	public void tearDown() throws IOException {
		ConvexDB cdb = ConvexDB.lookup(DB_NAME);
		if (cdb != null) cdb.unregister(DB_NAME);
		if (server != null) { server.close(); server = null; }
		if (store != null) { store.close(); store = null; }
	}

	// ========== Helpers ==========

	/** Opens a fresh server + ConvexDB + database from a new temp Etch file. */
	private ConvexDB openNew() throws Exception {
		store = EtchStore.createTemp("persist-test");
		etchFile = store.getFile();
		return openExisting();
	}

	/** Reopens server + ConvexDB from the existing Etch file. */
	private ConvexDB openExisting() throws Exception {
		if (store == null) {
			store = EtchStore.create(etchFile);
		}
		server = ConvexDB.createNodeServer(store);
		server.launch();
		ConvexDB cdb = ConvexDB.connect(server.getCursor());
		cdb.database(DB_NAME); // ensure database path exists
		cdb.register(DB_NAME);
		return cdb;
	}

	/** Persists, unregisters, closes server and store. */
	private void persistAndClose() throws Exception {
		server.persistSnapshot(server.getLocalValue());
		ConvexDB cdb = ConvexDB.lookup(DB_NAME);
		if (cdb != null) cdb.unregister(DB_NAME);
		server.close(); server = null;
		store.close(); store = null;
	}

	/** Creates the test table on the given database. */
	private void createTable(SQLDatabase db) {
		db.tables().createTable(TABLE_NAME,
				new String[]{"ID", "LEID", "NM"},
				new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.VARCHAR});
	}

	/** Inserts rows [start, end) via JDBC PreparedStatement batch. */
	private void insertViaJdbc(int start, int end) throws Exception {
		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
			 PreparedStatement ps = conn.prepareStatement(
					 "INSERT INTO " + TABLE_NAME + " VALUES (?, ?, ?)")) {
			for (int i = start; i < end; i++) {
				ps.setInt(1, i);
				ps.setString(2, "LEID-" + i);
				ps.setString(3, "Name-" + i);
				ps.addBatch();
			}
			ps.executeBatch();
		}
	}

	/** Inserts rows [start, end) via lattice API. */
	private void insertViaLattice(SQLSchema tables, int start, int end) {
		for (int i = start; i < end; i++) {
			tables.insert(TABLE_NAME, Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i));
		}
	}

	/** Verifies row count via JDBC SELECT COUNT(*). */
	private void verifyJdbcCount(long expected) throws Exception {
		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM " + TABLE_NAME)) {
			assertTrue(rs.next());
			assertEquals(expected, rs.getLong("cnt"), "JDBC COUNT(*)");
		}
	}

	/** Verifies a specific row via JDBC PK lookup. */
	private void verifyJdbcRow(int id) throws Exception {
		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(
					 "SELECT LEID FROM " + TABLE_NAME + " WHERE ID = " + id)) {
			assertTrue(rs.next(), "Row ID=" + id + " should exist");
			assertEquals("LEID-" + id, rs.getString("LEID"));
		}
	}

	// ========== Tests ==========

	/**
	 * JDBC insert → persist → close → reopen → verify via JDBC.
	 */
	@Test
	public void testJdbcPersistAndRestore() throws Exception {
		ConvexDB cdb = openNew();
		createTable(cdb.database(DB_NAME));

		insertViaJdbc(0, 100);
		verifyJdbcCount(100);
		verifyJdbcRow(0);
		verifyJdbcRow(99);

		persistAndClose();

		// Reopen from same Etch file
		openExisting();

		verifyJdbcCount(100);
		verifyJdbcRow(0);
		verifyJdbcRow(99);
	}

	/**
	 * Multiple insert → persist → reopen cycles, verifying accumulation.
	 */
	@Test
	public void testMultiplePersistCycles() throws Exception {
		ConvexDB cdb = openNew();
		createTable(cdb.database(DB_NAME));

		// Cycle 1: insert 50
		insertViaJdbc(0, 50);
		verifyJdbcCount(50);
		persistAndClose();

		// Cycle 2: reopen, verify 50, insert 50 more
		openExisting();
		verifyJdbcCount(50);
		insertViaJdbc(50, 100);
		verifyJdbcCount(100);
		persistAndClose();

		// Cycle 3: reopen, verify all 100
		openExisting();
		verifyJdbcCount(100);
		verifyJdbcRow(0);
		verifyJdbcRow(49);
		verifyJdbcRow(50);
		verifyJdbcRow(99);
	}

	/**
	 * Lattice API insert → persist → reopen → verify via lattice API.
	 * Ensures the liveCount field in the state vector survives persistence.
	 */
	@Test
	public void testLatticePersistAndRestore() throws Exception {
		ConvexDB cdb = openNew();
		SQLDatabase db = cdb.database(DB_NAME);
		createTable(db);

		insertViaLattice(db.tables(), 0, 200);

		// Verify via lattice API before persist
		assertEquals(200, db.tables().getRowCount(TABLE_NAME));
		assertNotNull(db.tables().selectByKey(TABLE_NAME, CVMLong.create(0)));
		assertNotNull(db.tables().selectByKey(TABLE_NAME, CVMLong.create(199)));

		persistAndClose();

		// Reopen and verify via lattice API
		cdb = openExisting();
		db = cdb.database(DB_NAME);
		assertEquals(200, db.tables().getRowCount(TABLE_NAME),
				"liveCount should survive persist/restore");
		assertNotNull(db.tables().selectByKey(TABLE_NAME, CVMLong.create(0)));
		assertNotNull(db.tables().selectByKey(TABLE_NAME, CVMLong.create(199)));
	}

	/**
	 * Lattice and JDBC counts must agree after persist/restore.
	 */
	@Test
	public void testLatticeAndJdbcCountConsistency() throws Exception {
		ConvexDB cdb = openNew();
		SQLDatabase db = cdb.database(DB_NAME);
		createTable(db);

		insertViaLattice(db.tables(), 0, 75);
		insertViaJdbc(75, 150);

		long latticeCount = db.tables().getRowCount(TABLE_NAME);
		assertEquals(150, latticeCount);

		persistAndClose();
		cdb = openExisting();
		db = cdb.database(DB_NAME);

		assertEquals(latticeCount, db.tables().getRowCount(TABLE_NAME),
				"Lattice count should match after restore");
		verifyJdbcCount(latticeCount);
	}
}
