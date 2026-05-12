package convex.db.sql;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

import convex.db.ConvexDB;

import convex.db.calcite.ConvexType;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLSchema;
import convex.db.lattice.VersionedSQLSchema;
import convex.db.lattice.VersionedSQLTable;
import convex.etch.EtchStore;
import convex.node.NodeServer;

/**
 * Insert performance demo/benchmark for Convex DB.
 *
 * Uses a realistic setup: NodeServer with temp Etch persistence, SQLDatabase
 * connected through the cursor chain.
 *
 * Tests: 1. Standalone (no persistence, baseline) 2. Direct lattice insert via
 * NodeServer 3. JDBC individual INSERT statements 4. JDBC PreparedStatement 5.
 * JDBC PreparedStatement batch
 *
 * Each NodeServer test syncs to Etch storage at the end.
 */
public class InsertDemo {

	static final int ROW_COUNT = 200_000;
	static final int SAMPLE_COUNT = 20;
	static final int SAMPLE_INTERVAL = ROW_COUNT / SAMPLE_COUNT;
	static final String DB_NAME = "bench";
	static final String STORE_DIR = "/home/rob/etch/";
	static final Random RND = new Random();

	public static void main(String[] args) throws Exception {
		System.out.println("=== Convex DB Insert Benchmark ===");
		System.out.println("Row count: " + ROW_COUNT);
		System.out.println("Setup: NodeServer + EtchStore (permanent, dir=" + STORE_DIR + ")\n");

		benchmarkStandalone();
		System.gc();
		benchmarkDirectLattice();
		System.gc();
		// benchmarkJdbcIndividual();
		benchmarkJdbcPrepared();
		System.gc();
		benchmarkJdbcPreparedBatch();
		System.gc();

		System.out.println("\nDone.");
	}

	// ========== Benchmarks ==========

	/**
	 * Pure baseline: standalone VersionedSQLSchema (no NodeServer, no persistence
	 * Nanotime Values INSe)
	 */
	static void benchmarkStandalone() throws Exception {
		VersionedSQLSchema schema = VersionedSQLSchema.create();
		createTable(schema);

		List<long[]> samples = new ArrayList<>();
		long memBefore = usedHeap();
		long start = System.nanoTime();
		for (int i = 0; i < ROW_COUNT; i++) {
			schema.insert("t", Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i, CVMLong.create(RND.nextLong())));
			if ((i + 1) % SAMPLE_INTERVAL == 0)
				samples.add(new long[] { i + 1, System.nanoTime() - start, usedHeap() - memBefore });
		}
		// Update pass: re-insert rows near id=100,000 with fresh random values → UPDATE
		// history entries
		for (int i = 99_995; i <= 100_005; i++)
			schema.insert("t", Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i, CVMLong.create(RND.nextLong())));
		long elapsed = System.nanoTime() - start;
		long memAfter = usedHeap();

		report("Standalone (no persist)", elapsed, ROW_COUNT, memAfter - memBefore);
		printProgress(samples);
		verify(schema, ROW_COUNT, "Standalone");
		printRowCount(schema, "before delete");
		schema.deleteByKey("t", CVMLong.create(100_000));
		printRowCount(schema, "after delete id=100,000");
		printHistory(schema, 100_000);
	}

	/**
	 * Direct VersionedSQLSchema.insert() via NodeServer cursor chain
	 */
	static void benchmarkDirectLattice() throws Exception {
		File storeFile = new File(STORE_DIR, "direct.etch");
		EtchStore store = EtchStore.create(storeFile);
		NodeServer<?> server = ConvexDB.createNodeServer(store);
		server.launch();
		SQLDatabase db = SQLDatabase.connect(server.getCursor(), DB_NAME);
		VersionedSQLSchema schema = VersionedSQLSchema.wrap(db.tables());
		createTable(schema);

		List<long[]> samples = new ArrayList<>();
		long memBefore = usedHeap();
		long start = System.nanoTime();
		for (int i = 0; i < ROW_COUNT; i++) {
			schema.insert("t", Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i, CVMLong.create(RND.nextLong())));
			if ((i + 1) % SAMPLE_INTERVAL == 0)
				samples.add(new long[] { i + 1, System.nanoTime() - start, usedHeap() - memBefore });
		}
		// Update pass: re-insert rows near id=100,000 with fresh random values → UPDATE
		// history entries
		for (int i = 99_995; i <= 100_005; i++)
			schema.insert("t", Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i, CVMLong.create(RND.nextLong())));
		long elapsed = System.nanoTime() - start;
		long memAfter = usedHeap();

		report("Direct lattice insert", elapsed, ROW_COUNT, memAfter - memBefore);
		printProgress(samples);
		syncToStorage(server);
		reportFileStats(storeFile, schema.getRowCount("t"));
		verify(schema, ROW_COUNT, "Direct lattice");
		printRowCount(schema, "before delete");
		schema.deleteByKey("t", CVMLong.create(100_000));
		printRowCount(schema, "after delete id=100,000");
		printHistory(schema, 100_000);
		server.close();
		store.close();
	}

	/**
	 * Individual JDBC INSERT statements — full Calcite parse/plan/compile per
	 * statement
	 */
	static void benchmarkJdbcIndividual() throws Exception {
		File storeFile = new File(STORE_DIR, "jdbc-individual.etch");
		EtchStore store = EtchStore.create(storeFile);
		NodeServer<?> server = ConvexDB.createNodeServer(store);
		server.launch();
		ConvexDB cdb = ConvexDB.connect(server.getCursor());
		SQLDatabase db = cdb.database(DB_NAME);
		SQLSchema schema = db.tables();
		createTable(schema);
		cdb.register(DB_NAME);

		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
				Statement stmt = conn.createStatement()) {

			// Warm up: trigger Calcite class loading / JIT
			stmt.executeUpdate("INSERT INTO t VALUES (-1, 'warm', 'up', 0)");

			List<long[]> samples = new ArrayList<>();
			long memBefore = usedHeap();
			long start = System.nanoTime();
			for (int i = 0; i < ROW_COUNT; i++) {
				stmt.executeUpdate(
						"INSERT INTO t VALUES (" + i + ", 'LEID-" + i + "', 'Name-" + i + "', " + RND.nextLong() + ")");
				if ((i + 1) % SAMPLE_INTERVAL == 0)
					samples.add(new long[] { i + 1, System.nanoTime() - start, usedHeap() - memBefore });
			}
			long elapsed = System.nanoTime() - start;
			long memAfter = usedHeap();

			report("JDBC individual INSERT", elapsed, ROW_COUNT, memAfter - memBefore);
			printProgress(samples);
			syncToStorage(server);
		}
		reportFileStats(storeFile, schema.getRowCount("t"));
		verify(schema, ROW_COUNT, "JDBC individual");
		cdb.unregister(DB_NAME);
		server.close();
		store.close();
	}

	/**
	 * JDBC PreparedStatement — parse/plan/compile once, bind params each time
	 */
	static void benchmarkJdbcPrepared() throws Exception {
		File storeFile = new File(STORE_DIR, "jdbc-prepared.etch");
		EtchStore store = EtchStore.create(storeFile);
		NodeServer<?> server = ConvexDB.createNodeServer(store);
		server.launch();
		ConvexDB cdb = ConvexDB.connect(server.getCursor());
		SQLDatabase db = cdb.database(DB_NAME);
		SQLSchema schema = db.tables();
		createTable(schema);
		cdb.register(DB_NAME);

		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
				PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?, ?, ?, ?)")) {

			List<long[]> samples = new ArrayList<>();
			long memBefore = usedHeap();
			long start = System.nanoTime();
			for (int i = 0; i < ROW_COUNT; i++) {
				ps.setInt(1, i);
				ps.setString(2, "LEID-" + i);
				ps.setString(3, "Name-" + i);
				ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
				if ((i + 1) % SAMPLE_INTERVAL == 0)
					samples.add(new long[] { i + 1, System.nanoTime() - start, usedHeap() - memBefore });
			}
			long elapsed = System.nanoTime() - start;
			long memAfter = usedHeap();

			report("JDBC PreparedStatement", elapsed, ROW_COUNT, memAfter - memBefore);
			printProgress(samples);
			syncToStorage(server);
		}
		reportFileStats(storeFile, schema.getRowCount("t"));
		verify(schema, ROW_COUNT, "JDBC PreparedStatement");
		printRowCount(schema, "total");
		cdb.unregister(DB_NAME);
		server.close();
		store.close();
	}

	/**
	 * PreparedStatement batch — compile once, batch all parameter bindings
	 */
	static void benchmarkJdbcPreparedBatch() throws Exception {
		File storeFile = new File(STORE_DIR, "jdbc-batch.etch");
		EtchStore store = EtchStore.create(storeFile);
		NodeServer<?> server = ConvexDB.createNodeServer(store);
		server.launch();
		ConvexDB cdb = ConvexDB.connect(server.getCursor());
		SQLDatabase db = cdb.database(DB_NAME);
		SQLSchema schema = db.tables();
		createTable(schema);
		cdb.register(DB_NAME);

		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
				PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?, ?, ?, ?)")) {

			List<long[]> samples = new ArrayList<>();
			long memBefore = usedHeap();
			long start = System.nanoTime();
			for (int i = 0; i < ROW_COUNT; i++) {
				ps.setInt(1, i);
				ps.setString(2, "LEID-" + i);
				ps.setString(3, "Name-" + i);
				ps.setLong(4, RND.nextLong());
				ps.addBatch();
				if ((i + 1) % SAMPLE_INTERVAL == 0) {
					ps.executeBatch();
					samples.add(new long[] { i + 1, System.nanoTime() - start, usedHeap() - memBefore });
				}
			}
			long elapsed = System.nanoTime() - start;
			long memAfter = usedHeap();

			report("JDBC PreparedStmt batch", elapsed, ROW_COUNT, memAfter - memBefore);
			printProgress(samples);
			syncToStorage(server);
		}
		reportFileStats(storeFile, schema.getRowCount("t"));
		verify(schema, ROW_COUNT, "JDBC PreparedStmt batch");
		printRowCount(schema, "total");
		cdb.unregister(DB_NAME);
		server.close();
		store.close();
	}

	// ========== Helpers ==========

	static void createTable(SQLSchema schema) {
		schema.createTable("t", new String[] { "ID", "LEID", "NM", "RND" },
				new ConvexType[] { ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.VARCHAR, ConvexType.INTEGER });
	}

	static void syncToStorage(NodeServer<?> server) throws Exception {
		long start = System.nanoTime();
		server.persistSnapshot(server.getLocalValue());
		long elapsed = System.nanoTime() - start;
		System.out.printf("  Sync to Etch:          %,.1f ms%n", elapsed / 1_000_000.0);
	}

	/**
	 * Smoke check: verifies row count via lattice API and JDBC SELECT COUNT, then
	 * spot-checks first (ID=0) and last (ID=ROW_COUNT-1) rows via JDBC.
	 */
	static void verify(SQLSchema schema, int minExpectedRows, String label) throws Exception {
		long latticeCount = schema.getRowCount("t");
		check(latticeCount >= minExpectedRows, label,
				"Lattice row count: expected >= " + minExpectedRows + ", got " + latticeCount);

		// JDBC verification only when a ConvexDB is registered (standalone dbs
		// have their own cursor, not reachable via the JDBC registry)
		ConvexDB vcdb = ConvexDB.lookup(DB_NAME);
		if (vcdb != null) {
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
				check(("LEID-" + lastId).equals(rs.getString("LEID")), label, "Row ID=" + lastId + " LEID mismatch");
			}
		}
		System.out.println("  VERIFIED OK (" + latticeCount + " rows)");
	}

	static void check(boolean condition, String testLabel, String message) {
		if (!condition) {
			throw new AssertionError("[" + testLabel + "] " + message);
		}
	}

	static void reportFileStats(File file, long rowCount) {
		long fileBytes = file.length();
		double fileMB = fileBytes / (1024.0 * 1024.0);
		double bytesPerRow = rowCount > 0 ? (double) fileBytes / rowCount : 0;
		System.out.printf("  File: %-40s  %,.1f MB  (%,d rows  =  %.0f bytes/row)%n", file.getName(), fileMB, rowCount,
				bytesPerRow);
	}

	/**
	 * Prints a per-sample table: rows inserted, cumulative time, instantaneous
	 * rate, heap delta.
	 */
	static void printProgress(List<long[]> samples) {
		System.out.printf("  %-12s  %-10s  %-14s  %-12s%n", "Rows", "Time(ms)", "Rate(rows/s)", "Heap delta");
		long prevRows = 0;
		long prevNs = 0;
		for (long[] s : samples) {
			long rows = s[0];
			long totalNs = s[1];
			long heapBytes = s[2];
			long intervalRows = rows - prevRows;
			long intervalNs = totalNs - prevNs;
			double intervalRate = intervalRows / (intervalNs / 1_000_000_000.0);
			double heapMB = heapBytes / (1024.0 * 1024.0);
			System.out.printf("  %-,12d  %-,10.0f  %-,14.0f  %+.1f MB%n", rows, totalNs / 1_000_000.0, intervalRate,
					heapMB);
			prevRows = rows;
			prevNs = totalNs;
		}
	}

	static void printRowCount(SQLSchema schema, String label) {
		long count = schema.getRowCount("t");
		System.out.printf("  Live rows in 't' (%s): %,d%n", label, count);
	}

	static void printRowCount(VersionedSQLSchema schema, String label) {
		long live = schema.getRowCount("t");
		VersionedSQLTable table = schema.getLiveTable("t");
		long history = table != null ? table.getHistoryIndex().count() : 0;
		System.out.printf("  Live rows in 't' (%s): %,d  (history entries: %,d)%n", label, live, history);
	}

	static long usedHeap() {
		Runtime rt = Runtime.getRuntime();
		return rt.totalMemory() - rt.freeMemory();
	}

	/**
	 * Prints the full history for a row in table "t" by primary key id. Shows each
	 * change event: type, nanotime, and values.
	 */
	static void printHistory(VersionedSQLSchema schema, long id) {
		System.out.printf("%n  --- History for id=%d ---%n", id);
		List<AVector<ACell>> history = schema.getHistory("t", CVMLong.create(id));
		if (history.isEmpty()) {
			System.out.println("  (no history — id not found or JDBC path used)");
			return;
		}
		System.out.printf("  %-8s  %-22s  %s%n", "Type", "Nanotime", "Values");
		for (AVector<ACell> entry : history) {
			long ts = ((CVMLong) entry.get(1)).longValue();
			long ct = ((CVMLong) entry.get(2)).longValue();
			ACell vals = entry.get(0);
			String type = switch ((int) ct) {
			case (int) VersionedSQLTable.CT_INSERT -> "INSERT";
			case (int) VersionedSQLTable.CT_UPDATE -> "UPDATE";
			case (int) VersionedSQLTable.CT_DELETE -> "DELETE";
			default -> "UNKNOWN";
			};
			System.out.printf("  %-8s  %-22d  %s%n", type, ts, vals == null ? "DELETED" : vals);
		}
		System.out.println();
	}

	static void report(String label, long elapsedNs, int count, long heapDeltaBytes) {
		double ms = elapsedNs / 1_000_000.0;
		double rate = count / (ms / 1000.0);
		double heapMB = heapDeltaBytes / (1024.0 * 1024.0);
		System.out.printf("%-25s %,.0f ms  (%,.0f rows/sec)  heap delta: %+,.1f MB%n", label, ms, rate, heapMB);
	}
}
