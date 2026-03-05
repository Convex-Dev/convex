package convex.db.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.calcite.ConvexSchemaFactory;
import convex.db.calcite.ConvexTable;
import convex.db.calcite.ConvexType;
import convex.core.data.Strings;
import convex.db.lattice.LatticeTables;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.TableStoreLattice;
import convex.etch.EtchStore;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.RootLatticeCursor;
import convex.lattice.generic.MapLattice;
import convex.node.NodeConfig;
import convex.node.NodeServer;

/**
 * Insert performance demo/benchmark for Convex DB.
 *
 * Uses a realistic setup: NodeServer with temp Etch persistence,
 * SQLDatabase connected through the cursor chain.
 *
 * Tests:
 * 1. Direct lattice insert (baseline)
 * 2. JDBC individual INSERT statements
 * 3. JDBC PreparedStatement
 * 4. JDBC PreparedStatement batch
 *
 * Each test syncs to Etch storage at the end.
 */
public class InsertDemo {

	static final int ROW_COUNT = 10_000;

	// Shared infrastructure
	static final AKeyPair KP = AKeyPair.generate();
	static final String DB_NAME = "bench";
	static final MapLattice<AString, Index<AString, AVector<ACell>>> DB_LATTICE =
			MapLattice.create(TableStoreLattice.INSTANCE);

	public static void main(String[] args) throws Exception {
		System.out.println("=== Convex DB Insert Benchmark ===");
		System.out.println("Row count: " + ROW_COUNT);
		System.out.println("Setup: NodeServer + EtchStore (temp)\n");

		benchmarkDirectStandalone();
		benchmarkCursorOnly();
		benchmarkDirectLattice();
		benchmarkJdbcIndividual();
		benchmarkJdbcPrepared();
		benchmarkJdbcPreparedBatch();

		System.out.println("\nDone.");
	}

	/**
	 * Creates a NodeServer with temp Etch persistence and connects an SQLDatabase.
	 * Returns [nodeServer, sqlDatabase].
	 */
	static Object[] createNodeAndDb() throws Exception {
		EtchStore store = EtchStore.createTemp();
		NodeConfig config = NodeConfig.port(-1); // local-only, no network
		NodeServer<AHashMap<AString, Index<AString, AVector<ACell>>>> server =
				new NodeServer<>(DB_LATTICE, store, config);
		server.launch();

		ALatticeCursor<?> cursor = server.getCursor();
		SQLDatabase db = SQLDatabase.connect(cursor, DB_NAME);
		return new Object[] { server, db, store };
	}

	/**
	 * Syncs cursor state to Etch storage and reports timing.
	 */
	static void syncToStorage(NodeServer<?> server, String label) throws Exception {
		long start = System.nanoTime();
		server.persistSnapshot(server.getLocalValue());
		long elapsed = System.nanoTime() - start;
		System.out.printf("  Sync to Etch:          %,.1f ms%n", elapsed / 1_000_000.0);
	}

	/**
	 * Pure baseline: standalone LatticeTables (no NodeServer, no cursor chain)
	 */
	static void benchmarkDirectStandalone() throws Exception {
		SQLDatabase db = SQLDatabase.create(DB_NAME, KP);
		LatticeTables tables = db.tables();
		tables.createTable("t", new String[]{"ID", "LEID", "NM"},
				new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.VARCHAR});

		long start = System.nanoTime();
		for (int i = 0; i < ROW_COUNT; i++) {
			tables.insert("t", Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i));
		}
		long elapsed = System.nanoTime() - start;

		report("Standalone (no cursor)", elapsed, ROW_COUNT);
		verify(db, ROW_COUNT, "Standalone");
	}

	/**
	 * Micro-benchmark: raw cursor updateAndGet through DescendedCursor path.
	 * No LatticeTables — just measures cursor chain overhead.
	 */
	static void benchmarkCursorOnly() throws Exception {
		// Standalone cursor: direct AtomicReference on Index
		ALatticeCursor<Index<AString, AVector<ACell>>> standaloneCursor =
				Cursors.createLattice(TableStoreLattice.INSTANCE);
		AString key = Strings.create("k");
		AVector<ACell> val = Vectors.of(CVMLong.create(0));

		long start = System.nanoTime();
		for (int i = 0; i < ROW_COUNT; i++) {
			standaloneCursor.updateAndGet(store -> {
				if (store == null) return Index.of(key, val);
				return store.assoc(key, val);
			});
		}
		long elapsed1 = System.nanoTime() - start;
		report("Cursor: standalone", elapsed1, ROW_COUNT);

		// NodeServer cursor: DescendedCursor through HashMap path
		RootLatticeCursor<AHashMap<AString, Index<AString, AVector<ACell>>>> rootCursor =
				Cursors.createLattice(DB_LATTICE);
		ALatticeCursor<Index<AString, AVector<ACell>>> descendedCursor =
				rootCursor.path(Strings.create(DB_NAME));

		start = System.nanoTime();
		for (int i = 0; i < ROW_COUNT; i++) {
			descendedCursor.updateAndGet(store -> {
				if (store == null) return Index.of(key, val);
				return store.assoc(key, val);
			});
		}
		long elapsed2 = System.nanoTime() - start;
		report("Cursor: descended", elapsed2, ROW_COUNT);
		System.out.printf("  Overhead: %.1fx%n", (double) elapsed2 / elapsed1);
	}

	/**
	 * Direct LatticeTables.insert() via NodeServer cursor chain
	 */
	static void benchmarkDirectLattice() throws Exception {
		Object[] setup = createNodeAndDb();
		@SuppressWarnings("unchecked")
		NodeServer<AHashMap<AString, Index<AString, AVector<ACell>>>> server =
				(NodeServer<AHashMap<AString, Index<AString, AVector<ACell>>>>) setup[0];
		SQLDatabase db = (SQLDatabase) setup[1];
		EtchStore store = (EtchStore) setup[2];

		LatticeTables tables = db.tables();
		tables.createTable("t", new String[]{"ID", "LEID", "NM"},
				new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.VARCHAR});

		long start = System.nanoTime();
		for (int i = 0; i < ROW_COUNT; i++) {
			tables.insert("t", Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i));
		}
		long elapsed = System.nanoTime() - start;

		report("Direct lattice insert", elapsed, ROW_COUNT);
		syncToStorage(server, "Direct lattice");
		verify(db, ROW_COUNT, "Direct lattice");

		server.close();
		store.close();
	}

	/**
	 * Individual JDBC INSERT statements — full parse/plan/compile per statement
	 */
	static void benchmarkJdbcIndividual() throws Exception {
		Object[] setup = createNodeAndDb();
		@SuppressWarnings("unchecked")
		NodeServer<AHashMap<AString, Index<AString, AVector<ACell>>>> server =
				(NodeServer<AHashMap<AString, Index<AString, AVector<ACell>>>>) setup[0];
		SQLDatabase db = (SQLDatabase) setup[1];
		EtchStore store = (EtchStore) setup[2];

		db.tables().createTable("t", new String[]{"ID", "LEID", "NM"},
				new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.VARCHAR});
		ConvexSchemaFactory.register(DB_NAME, db);

		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
			 Statement stmt = conn.createStatement()) {

			// Warm up
			stmt.executeUpdate("INSERT INTO t VALUES (-1, 'warm', 'up')");

			ConvexTable.resetScanCount();
			long start = System.nanoTime();
			for (int i = 0; i < ROW_COUNT; i++) {
				stmt.executeUpdate("INSERT INTO t VALUES (" + i + ", 'LEID-" + i + "', 'Name-" + i + "')");
			}
			long elapsed = System.nanoTime() - start;

			report("JDBC individual INSERT", elapsed, ROW_COUNT);
			System.out.println("  Table scans: " + ConvexTable.getScanCount());
			syncToStorage(server, "JDBC individual");
		}
		verify(db, ROW_COUNT + 1, "JDBC individual"); // +1 for warm-up row
		ConvexSchemaFactory.unregister(DB_NAME);
		server.close();
		store.close();
	}

	/**
	 * JDBC PreparedStatement — parse/plan/compile once, bind params each time
	 */
	static void benchmarkJdbcPrepared() throws Exception {
		Object[] setup = createNodeAndDb();
		@SuppressWarnings("unchecked")
		NodeServer<AHashMap<AString, Index<AString, AVector<ACell>>>> server =
				(NodeServer<AHashMap<AString, Index<AString, AVector<ACell>>>>) setup[0];
		SQLDatabase db = (SQLDatabase) setup[1];
		EtchStore store = (EtchStore) setup[2];

		db.tables().createTable("t", new String[]{"ID", "LEID", "NM"},
				new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.VARCHAR});
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
			syncToStorage(server, "JDBC prepared");
		}
		verify(db, ROW_COUNT, "JDBC PreparedStatement");
		ConvexSchemaFactory.unregister(DB_NAME);
		server.close();
		store.close();
	}

	/**
	 * PreparedStatement batch — compile once, batch parameter bindings
	 */
	static void benchmarkJdbcPreparedBatch() throws Exception {
		Object[] setup = createNodeAndDb();
		@SuppressWarnings("unchecked")
		NodeServer<AHashMap<AString, Index<AString, AVector<ACell>>>> server =
				(NodeServer<AHashMap<AString, Index<AString, AVector<ACell>>>>) setup[0];
		SQLDatabase db = (SQLDatabase) setup[1];
		EtchStore store = (EtchStore) setup[2];

		db.tables().createTable("t", new String[]{"ID", "LEID", "NM"},
				new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.VARCHAR});
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
			syncToStorage(server, "JDBC PS batch");
		}
		verify(db, ROW_COUNT, "JDBC PreparedStmt batch");
		ConvexSchemaFactory.unregister(DB_NAME);
		server.close();
		store.close();
	}

	/**
	 * Smoke check: verifies row count via lattice API and JDBC SELECT COUNT,
	 * then spot-checks first (ID=0) and last (ID=ROW_COUNT-1) rows via JDBC.
	 */
	static void verify(SQLDatabase db, int minExpectedRows, String label) throws Exception {
		// 1. Lattice row count
		long latticeCount = db.tables().getRowCount("t");
		check(latticeCount >= minExpectedRows, label,
				"Lattice row count: expected >= " + minExpectedRows + ", got " + latticeCount);

		// 2. JDBC SELECT COUNT(*)
		ConvexSchemaFactory.register(DB_NAME, db);
		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
			 Statement stmt = conn.createStatement()) {

			ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM t");
			rs.next();
			long jdbcCount = rs.getLong("cnt");
			check(jdbcCount >= minExpectedRows, label,
					"JDBC COUNT(*): expected >= " + minExpectedRows + ", got " + jdbcCount);
			check(jdbcCount == latticeCount, label,
					"Count mismatch: lattice=" + latticeCount + " vs JDBC=" + jdbcCount);

			// 3. Spot-check first row (ID=0)
			rs = stmt.executeQuery("SELECT ID, LEID, NM FROM t WHERE ID = 0");
			check(rs.next(), label, "Row ID=0 not found");
			check("LEID-0".equals(rs.getString("LEID")), label,
					"Row ID=0 LEID: expected 'LEID-0', got '" + rs.getString("LEID") + "'");

			// 4. Spot-check last row (ID=ROW_COUNT-1)
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
