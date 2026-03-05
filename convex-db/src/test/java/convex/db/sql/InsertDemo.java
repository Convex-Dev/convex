package convex.db.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import convex.core.crypto.AKeyPair;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.calcite.ConvexSchemaFactory;
import convex.db.calcite.ConvexTable;
import convex.db.calcite.ConvexType;
import convex.db.lattice.SQLDatabase;
import convex.etch.EtchStore;
import convex.node.NodeServer;

/**
 * Insert performance demo/benchmark for Convex DB.
 *
 * Uses a realistic setup: NodeServer with temp Etch persistence,
 * SQLDatabase connected through the cursor chain.
 *
 * Tests:
 * 1. Standalone (no persistence, baseline)
 * 2. Direct lattice insert via NodeServer
 * 3. JDBC individual INSERT statements
 * 4. JDBC PreparedStatement
 * 5. JDBC PreparedStatement batch
 *
 * Each NodeServer test syncs to Etch storage at the end.
 */
public class InsertDemo {

	static final int ROW_COUNT = 10_000;
	static final String DB_NAME = "bench";

	public static void main(String[] args) throws Exception {
		System.out.println("=== Convex DB Insert Benchmark ===");
		System.out.println("Row count: " + ROW_COUNT);
		System.out.println("Setup: NodeServer + EtchStore (temp)\n");

		benchmarkStandalone();
		benchmarkDirectLattice();
		benchmarkJdbcIndividual();
		benchmarkJdbcPrepared();
		benchmarkJdbcPreparedBatch();

		System.out.println("\nDone.");
	}

	// ========== Benchmarks ==========

	/**
	 * Pure baseline: standalone SQLDatabase (no NodeServer, no persistence)
	 */
	static void benchmarkStandalone() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create(DB_NAME, kp);
		createTable(db);

		long start = System.nanoTime();
		for (int i = 0; i < ROW_COUNT; i++) {
			db.tables().insert("t", Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i));
		}
		long elapsed = System.nanoTime() - start;

		report("Standalone (no persist)", elapsed, ROW_COUNT);
		verify(db, ROW_COUNT, "Standalone");
	}

	/**
	 * Direct LatticeTables.insert() via NodeServer cursor chain
	 */
	static void benchmarkDirectLattice() throws Exception {
		EtchStore store = EtchStore.createTemp();
		NodeServer<?> server = SQLDatabase.createNodeServer(store);
		server.launch();
		SQLDatabase db = SQLDatabase.connect(server.getCursor(), DB_NAME);
		createTable(db);

		long start = System.nanoTime();
		for (int i = 0; i < ROW_COUNT; i++) {
			db.tables().insert("t", Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i));
		}
		long elapsed = System.nanoTime() - start;

		report("Direct lattice insert", elapsed, ROW_COUNT);
		syncToStorage(server);
		verify(db, ROW_COUNT, "Direct lattice");
		server.close();
		store.close();
	}

	/**
	 * Individual JDBC INSERT statements — full Calcite parse/plan/compile per statement
	 */
	static void benchmarkJdbcIndividual() throws Exception {
		EtchStore store = EtchStore.createTemp();
		NodeServer<?> server = SQLDatabase.createNodeServer(store);
		server.launch();
		SQLDatabase db = SQLDatabase.connect(server.getCursor(), DB_NAME);
		createTable(db);
		ConvexSchemaFactory.register(DB_NAME, db);

		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
			 Statement stmt = conn.createStatement()) {

			// Warm up: trigger Calcite class loading / JIT
			stmt.executeUpdate("INSERT INTO t VALUES (-1, 'warm', 'up')");

			ConvexTable.resetScanCount();
			long start = System.nanoTime();
			for (int i = 0; i < ROW_COUNT; i++) {
				stmt.executeUpdate("INSERT INTO t VALUES (" + i + ", 'LEID-" + i + "', 'Name-" + i + "')");
			}
			long elapsed = System.nanoTime() - start;

			report("JDBC individual INSERT", elapsed, ROW_COUNT);
			System.out.println("  Table scans: " + ConvexTable.getScanCount());
			syncToStorage(server);
		}
		verify(db, ROW_COUNT, "JDBC individual");
		ConvexSchemaFactory.unregister(DB_NAME);
		server.close();
		store.close();
	}

	/**
	 * JDBC PreparedStatement — parse/plan/compile once, bind params each time
	 */
	static void benchmarkJdbcPrepared() throws Exception {
		EtchStore store = EtchStore.createTemp();
		NodeServer<?> server = SQLDatabase.createNodeServer(store);
		server.launch();
		SQLDatabase db = SQLDatabase.connect(server.getCursor(), DB_NAME);
		createTable(db);
		ConvexSchemaFactory.register(DB_NAME, db);

		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
			 PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?, ?, ?)")) {

			ConvexTable.resetScanCount();
			long start = System.nanoTime();
			for (int i = 0; i < ROW_COUNT; i++) {
				ps.setInt(1, i);
				ps.setString(2, "LEID-" + i);
				ps.setString(3, "Name-" + i);
				ps.executeUpdate();
			}
			long elapsed = System.nanoTime() - start;

			report("JDBC PreparedStatement", elapsed, ROW_COUNT);
			System.out.println("  Table scans: " + ConvexTable.getScanCount());
			syncToStorage(server);
		}
		verify(db, ROW_COUNT, "JDBC PreparedStatement");
		ConvexSchemaFactory.unregister(DB_NAME);
		server.close();
		store.close();
	}

	/**
	 * PreparedStatement batch — compile once, batch all parameter bindings
	 */
	static void benchmarkJdbcPreparedBatch() throws Exception {
		EtchStore store = EtchStore.createTemp();
		NodeServer<?> server = SQLDatabase.createNodeServer(store);
		server.launch();
		SQLDatabase db = SQLDatabase.connect(server.getCursor(), DB_NAME);
		createTable(db);
		ConvexSchemaFactory.register(DB_NAME, db);

		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
			 PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?, ?, ?)")) {

			ConvexTable.resetScanCount();
			long start = System.nanoTime();
			for (int i = 0; i < ROW_COUNT; i++) {
				ps.setInt(1, i);
				ps.setString(2, "LEID-" + i);
				ps.setString(3, "Name-" + i);
				ps.addBatch();
			}
			ps.executeBatch();
			long elapsed = System.nanoTime() - start;

			report("JDBC PreparedStmt batch", elapsed, ROW_COUNT);
			System.out.println("  Table scans: " + ConvexTable.getScanCount());
			syncToStorage(server);
		}
		verify(db, ROW_COUNT, "JDBC PreparedStmt batch");
		ConvexSchemaFactory.unregister(DB_NAME);
		server.close();
		store.close();
	}

	// ========== Helpers ==========

	static void createTable(SQLDatabase db) {
		db.tables().createTable("t", new String[]{"ID", "LEID", "NM"},
				new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.VARCHAR});
	}

	static void syncToStorage(NodeServer<?> server) throws Exception {
		long start = System.nanoTime();
		server.persistSnapshot(server.getLocalValue());
		long elapsed = System.nanoTime() - start;
		System.out.printf("  Sync to Etch:          %,.1f ms%n", elapsed / 1_000_000.0);
	}

	/**
	 * Smoke check: verifies row count via lattice API and JDBC SELECT COUNT,
	 * then spot-checks first (ID=0) and last (ID=ROW_COUNT-1) rows via JDBC.
	 */
	static void verify(SQLDatabase db, int minExpectedRows, String label) throws Exception {
		long latticeCount = db.tables().getRowCount("t");
		check(latticeCount >= minExpectedRows, label,
				"Lattice row count: expected >= " + minExpectedRows + ", got " + latticeCount);

		ConvexSchemaFactory.register(DB_NAME, db);
		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
			 Statement stmt = conn.createStatement()) {

			ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM t");
			rs.next();
			long jdbcCount = rs.getLong("cnt");
			check(jdbcCount == latticeCount, label,
					"Count mismatch: lattice=" + latticeCount + " vs JDBC=" + jdbcCount);

			// Spot-check first row
			rs = stmt.executeQuery("SELECT ID, LEID, NM FROM t WHERE ID = 0");
			check(rs.next(), label, "Row ID=0 not found");
			check("LEID-0".equals(rs.getString("LEID")), label,
					"Row ID=0 LEID: expected 'LEID-0', got '" + rs.getString("LEID") + "'");

			// Spot-check last row
			int lastId = ROW_COUNT - 1;
			rs = stmt.executeQuery("SELECT ID, LEID, NM FROM t WHERE ID = " + lastId);
			check(rs.next(), label, "Row ID=" + lastId + " not found");
			check(("LEID-" + lastId).equals(rs.getString("LEID")), label,
					"Row ID=" + lastId + " LEID mismatch");
		}
		System.out.println("  VERIFIED OK (" + latticeCount + " rows)");
	}

	static void check(boolean condition, String testLabel, String message) {
		if (!condition) {
			throw new AssertionError("[" + testLabel + "] " + message);
		}
	}

	static void report(String label, long elapsedNs, int count) {
		double ms = elapsedNs / 1_000_000.0;
		double rate = count / (ms / 1000.0);
		System.out.printf("%-25s %,.0f ms  (%,.0f rows/sec)%n", label, ms, rate);
	}
}
