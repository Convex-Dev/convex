package convex.db.sql;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.ConvexDB;
import convex.db.calcite.ConvexType;
import convex.db.store.SegmentedEtchStore;
import convex.db.lattice.RowBlock;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLRow;
import convex.db.lattice.SQLSchema;
import convex.db.lattice.SQLTable;
import convex.db.lattice.VersionedSQLSchema;
import convex.etch.EtchStore;
import convex.node.NodeServer;

/**
 * Comparison benchmark: Convex DB vs MariaDB.
 *
 * <p>Measures insert throughput, file size, and estimated compressed size for:
 * <ul>
 *   <li>Convex JDBC PreparedStatement</li>
 *   <li>Convex JDBC PreparedStatement batch</li>
 *   <li>MariaDB JDBC PreparedStatement</li>
 *   <li>MariaDB JDBC PreparedStatement batch</li>
 * </ul>
 *
 * <p>MariaDB connection is controlled by system properties:
 * <pre>
 *   -Dmariadb.user=root   (default: root)
 *   -Dmariadb.pass=       (default: empty)
 *   -Dmariadb.host=localhost (default: localhost)
 *   -Dmariadb.port=3306   (default: 3306)
 * </pre>
 *
 * <p>Etch files are written to STORE_DIR.
 */
public class DBComparisonBench {

	static final int    ROW_COUNT      = 100_000;
	static final int    BATCH_SIZE     = 1_000;
	static final int    SINGLE_WARMUP  = 200;   // inserts before timing single-row
	static final int    SINGLE_SAMPLES = 1_000; // timed single-row inserts to average
	static final int    RW_OPS         = 10_000; // lookup/update ops per read-write bench
	static final int[]  REPLICATION_K  = {100, 1_000, 10_000}; // changes applied on node B
	static final String DB_NAME        = "bench";
	static final String TABLE_NAME     = "t";
	static final String TABLE_NAME_MEM = "t_mem";
	static final String STORE_DIR      = "/home/rob/etch/";
	static final Random RND            = new Random(42);

	// MariaDB connection params (overridable via -Dmariadb.* system properties)
	static final String MARIADB_HOST = System.getProperty("mariadb.host", "localhost");
	static final String MARIADB_PORT = System.getProperty("mariadb.port", "3306");
	static final String MARIADB_USER = System.getProperty("mariadb.user", "tempuser");
	static final String MARIADB_PASS = System.getProperty("mariadb.pass", "123456");
	static final String MARIADB_DB   = "temp";
	static final String MARIADB_URL  =
		"jdbc:mariadb://" + MARIADB_HOST + ":" + MARIADB_PORT + "/" + MARIADB_DB
		+ "?rewriteBatchedStatements=true&useServerPrepStmts=true";

	// PostgreSQL connection params (overridable via -Dpsql.* system properties)
	static final String PSQL_HOST = System.getProperty("psql.host", "localhost");
	static final String PSQL_PORT = System.getProperty("psql.port", "5432");
	static final String PSQL_USER = System.getProperty("psql.user", "tempuser");
	static final String PSQL_PASS = System.getProperty("psql.pass", "123456");
	static final String PSQL_DB   = System.getProperty("psql.db", "temp");
	static final String PSQL_URL  = "jdbc:postgresql://" + PSQL_HOST + ":" + PSQL_PORT + "/" + PSQL_DB;

	// ── Result record ──────────────────────────────────────────────────────────

	record BenchResult(
		String label,
		int    rows,
		long   elapsedNs,
		long   writtenBytes,     // actual data written (etchDataBytes for Etch, information_schema for MariaDB)
		long   allocatedBytes,   // OS-level file allocation (same as writtenBytes for MariaDB; storeFile.length() for Etch)
		long   compressedBytes,  // estimated gz size of writtenBytes (-1 if not measured)
		long   heapDeltaBytes,
		long   dbRowsBefore,     // live rows in DB before benchmark (-1 if not measured)
		long   dbRowsAfter       // live rows in DB after benchmark (-1 if not measured)
	) {
		// Backward-compatible constructor for benchmarks that don't measure row counts
		BenchResult(String label, int rows, long elapsedNs,
				long writtenBytes, long allocatedBytes, long compressedBytes, long heapDeltaBytes) {
			this(label, rows, elapsedNs, writtenBytes, allocatedBytes, compressedBytes, heapDeltaBytes, -1, -1);
		}
		double rowsPerSec()       { return rows / (elapsedNs / 1e9); }
		double writtenPerRow()    { return writtenBytes  > 0 ? (double) writtenBytes  / rows : -1; }
		double allocatedPerRow()  { return allocatedBytes > 0 ? (double) allocatedBytes / rows : -1; }
		double compPerRow()       { return compressedBytes > 0 ? (double) compressedBytes / rows : -1; }
		double writtenMB()        { return writtenBytes  > 0 ? writtenBytes  / (1024.0 * 1024.0) : -1; }
		double allocatedMB()      { return allocatedBytes > 0 ? allocatedBytes / (1024.0 * 1024.0) : -1; }
		double heapDeltaMB()      { return heapDeltaBytes / (1024.0 * 1024.0); }
	}

	// ── Replication result record ─────────────────────────────────────────────

	record ReplicationResult(
		String db,
		int    k,                // changes applied on node B
		long   payloadBytes,     // bytes that need to be transferred for sync
		long   fullTableBytes,   // full table size (= naive sync cost without dedup)
		String payloadNote       // how payload was measured
	) {
		double payloadKB()       { return payloadBytes / 1024.0; }
		double bytesPerChange()  { return (double) payloadBytes / k; }
		double fullTableMB()     { return fullTableBytes / (1024.0 * 1024.0); }
		double payloadPct()      { return fullTableBytes > 0 ? 100.0 * payloadBytes / fullTableBytes : -1; }
	}

	// ── Entry point ────────────────────────────────────────────────────────────

	public static void main(String[] args) throws Exception {
		// ── Compare mode ────────────────────────────────────────────────────
		if (args.length == 3 && "compare".equals(args[0])) {
			compareResultFiles(new File(args[1]), new File(args[2]));
			return;
		}

		System.out.println("=== Convex DB vs MariaDB / PostgreSQL — Insert Benchmark ===");
		System.out.printf("Rows: %,d  |  Batch size: %,d  |  Etch dir: %s%n%n", ROW_COUNT, BATCH_SIZE, STORE_DIR);

		List<BenchResult> results = new ArrayList<>();

		boolean mariaAvailable = checkMariaDB();
		if (mariaAvailable) {
			results.add(benchMariaDBPrepared());
			System.gc();
			results.add(benchMariaDBBatch());
			System.gc();
			results.add(benchMariaDBLoadFile());
			System.gc();
			results.add(benchMariaDBVersionedPrepared());
			System.gc();
			results.add(benchMariaDBVersionedBatch());
			System.gc();
			results.add(benchMariaDBVersionedLoadFile());
			System.gc();
			results.add(benchMariaDBMemoryPrepared());
			System.gc();
			results.add(benchMariaDBMemoryBatch());
			System.gc();
		} else {
			System.out.println("MariaDB not available — skipping MariaDB benchmarks.");
			System.out.printf("  Connect string: %s  user=%s%n%n", MARIADB_URL, MARIADB_USER);
		}
		boolean psqlAvailable = checkPostgres();
		if (psqlAvailable) {
			results.add(benchPostgresPrepared());
			System.gc();
			results.add(benchPostgresBatch());
			System.gc();
			results.add(benchPostgresCopy());
			System.gc();
		} else {
			System.out.println("PostgreSQL not available — skipping PostgreSQL benchmarks.");
			System.out.printf("  Connect string: %s  user=%s%n%n", PSQL_URL, PSQL_USER);
		}
		results.add(benchConvexDirect());
		System.gc();
		results.add(benchConvexDirectVersioned());
		System.gc();
		results.add(benchConvexPrepared());
		System.gc();
		results.add(benchConvexBatch());
		System.gc();

		printComparison(results);

		// ── Single-row latency ──────────────────────────────────────────────
		System.out.println("\n=== Single-row insert latency ===");
		System.out.printf("Warmup: %,d  |  Samples: %,d%n%n", SINGLE_WARMUP, SINGLE_SAMPLES);

		List<BenchResult> singleResults = new ArrayList<>();
		singleResults.add(benchConvexDirectSingle());
		System.gc();
		singleResults.add(benchConvexPreparedSingle());
		System.gc();
		if (mariaAvailable) {
			singleResults.add(benchMariaDBPreparedSingle());
			System.gc();
			singleResults.add(benchMariaDBLoadFileSingle());
			System.gc();
			singleResults.add(benchMariaDBVersionedPreparedSingle());
			System.gc();
			singleResults.add(benchMariaDBMemoryPreparedSingle());
			System.gc();
		}
		if (psqlAvailable) {
			singleResults.add(benchPostgresPreparedSingle());
			System.gc();
		}
		printSingleRowComparison(singleResults);

		// ── Read / Update benchmarks ────────────────────────────────────────
		System.out.printf("%n=== Read / Update benchmarks (ops: %,d on %,d-row dataset) ===%n%n", RW_OPS, ROW_COUNT);

		List<BenchResult> rwResults = new ArrayList<>();
		rwResults.addAll(benchConvexDirectRW());
		System.gc();
		rwResults.addAll(benchConvexJdbcRW());
		System.gc();
		if (mariaAvailable) {
			rwResults.addAll(benchMariaDBRW(false));
			System.gc();
			rwResults.addAll(benchMariaDBRW(true));
			System.gc();
			rwResults.addAll(benchMariaDBMemoryRW());
			System.gc();
		}
		if (psqlAvailable) {
			rwResults.addAll(benchPostgresRW());
			System.gc();
		}
		printRWComparison(rwResults);

		// ── Full scan benchmarks ────────────────────────────────────────────
		System.out.println("\n=== Full scan (SELECT * heap cost) ===");
		List<BenchResult> scanResults = new ArrayList<>();
		scanResults.add(benchConvexJdbcScan());
		System.gc();
		if (mariaAvailable) {
			scanResults.add(benchMariaDBScan());
			System.gc();
			scanResults.add(benchMariaDBMemoryScan());
			System.gc();
		}
		printScanComparison(scanResults);

		// ── Replication / Dedup benchmark ───────────────────────────────────
		//benchmarkReplication(mariaAvailable, psqlAvailable);

		// ── Merge latency benchmark ──────────────────────────────────────────
		System.out.println("\n=== Merge latency benchmark ===");
		List<BenchResult> mergeResults = benchConvexMergeLatency();
		System.gc();

		// ── Save all results ─────────────────────────────────────────────────
		List<BenchResult> all = new ArrayList<>();
		all.addAll(results);
		all.addAll(singleResults);
		all.addAll(rwResults);
		all.addAll(scanResults);
		all.addAll(mergeResults);
		File saved = saveResults(all);
		System.out.println("\nResults saved to: " + saved.getPath());
		autoCompare(saved);
		System.out.println("\nTo compare with a specific baseline run:");
		System.out.printf("  java DBComparisonBench compare <baseline.json> %s%n", saved.getName());

		System.out.println("\nDone.");
	}

	// ── Convex benchmarks ──────────────────────────────────────────────────────

	static BenchResult benchConvexDirect() throws Exception {
		System.out.println("--- Convex direct lattice insert (plain, no history) ---");
		File storeDir = new File(STORE_DIR, "cmp-convex-direct");
		SegmentedEtchStore store = SegmentedEtchStore.createFresh(storeDir);
		NodeServer<?> server = ConvexDB.createNodeServer(store);
		server.launch();
		SQLDatabase db = SQLDatabase.connect(server.getCursor(), DB_NAME);
		SQLSchema schema = db.tables();  // plain — matches JDBC benchmarks (no history overhead)
		createConvexTable(schema);

		long dbRowsBefore = countConvexRows(schema);
		long heapBefore = usedHeap();
		long start = System.nanoTime();

		List<AVector<ACell>> batch = new ArrayList<>(ROW_COUNT);
		for (int i = 0; i < ROW_COUNT; i++)
			batch.add(Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i, CVMLong.create(RND.nextLong())));
		schema.insertAll(TABLE_NAME, batch);
		batch = null; // release input batch — Index now holds compact Blobs; measure only those
		System.gc();

		long elapsed = System.nanoTime() - start;
		long heapDelta = usedHeap() - heapBefore;
		long dbRowsAfter = countConvexRows(schema);

		server.persistSnapshot(server.getLocalValue());
		long writtenBytes = etchDataBytes(store.getHotFile());
		long allocBytes   = store.getHotFile().length();
		long compBytes    = estimateCompressedSize(store.getHotFile());
		System.out.printf("  Elapsed: %,.0f ms  |  Written: %.1f MB  |  Alloc: %.1f MB  |  ~gz: %.1f MB  |  DB rows: %,d→%,d%n",
				elapsed / 1e6, writtenBytes / (1024.0 * 1024.0), allocBytes / (1024.0 * 1024.0), compBytes / (1024.0 * 1024.0),
				dbRowsBefore, dbRowsAfter);

		server.close();
		store.close();
		return new BenchResult("Convex direct", ROW_COUNT, elapsed, writtenBytes, allocBytes, compBytes, heapDelta, dbRowsBefore, dbRowsAfter);
	}

	static BenchResult benchConvexDirectVersioned() throws Exception {
		System.out.println("--- Convex direct lattice insert (versioned, with history) ---");
		File storeDir = new File(STORE_DIR, "cmp-convex-direct-versioned");
		SegmentedEtchStore store = SegmentedEtchStore.createFresh(storeDir);
		NodeServer<?> server = ConvexDB.createNodeServer(store);
		server.launch();
		SQLDatabase db = SQLDatabase.connect(server.getCursor(), DB_NAME);
		VersionedSQLSchema schema = VersionedSQLSchema.wrap(db.tables());
		createConvexTable(schema);

		long dbRowsBefore = countConvexRows(schema);
		long heapBefore = usedHeap();
		long start = System.nanoTime();

		List<AVector<ACell>> batch = new ArrayList<>(ROW_COUNT);
		for (int i = 0; i < ROW_COUNT; i++)
			batch.add(Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i, CVMLong.create(RND.nextLong())));
		schema.insertAll(TABLE_NAME, batch);
		batch = null; // release input batch before measuring Index-only heap
		System.gc();

		long elapsed = System.nanoTime() - start;
		long heapDelta = usedHeap() - heapBefore;
		long dbRowsAfter = countConvexRows(schema);

		server.persistSnapshot(server.getLocalValue());
		long writtenBytes = etchDataBytes(store.getHotFile());
		long allocBytes   = store.getHotFile().length();
		long compBytes    = estimateCompressedSize(store.getHotFile());
		System.out.printf("  Elapsed: %,.0f ms  |  Written: %.1f MB  |  Alloc: %.1f MB  |  ~gz: %.1f MB  |  DB rows: %,d→%,d%n",
				elapsed / 1e6, writtenBytes / (1024.0 * 1024.0), allocBytes / (1024.0 * 1024.0), compBytes / (1024.0 * 1024.0),
				dbRowsBefore, dbRowsAfter);

		server.close();
		store.close();
		return new BenchResult("Convex direct versioned", ROW_COUNT, elapsed, writtenBytes, allocBytes, compBytes, heapDelta, dbRowsBefore, dbRowsAfter);
	}

	static BenchResult benchConvexPrepared() throws Exception {
		System.out.println("--- Convex JDBC PreparedStatement ---");
		File storeDir = new File(STORE_DIR, "cmp-convex-prepared");
		SegmentedEtchStore store = SegmentedEtchStore.createFresh(storeDir);
		NodeServer<?> server = ConvexDB.createNodeServer(store);
		server.launch();
		ConvexDB cdb = ConvexDB.connect(server.getCursor());
		SQLDatabase db = cdb.database(DB_NAME);
		SQLSchema schema = db.tables();
		createConvexTable(schema);
		cdb.register(DB_NAME);

		long dbRowsBefore = countConvexRows(schema);
		long heapBefore = usedHeap();
		long start = System.nanoTime();

		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
				PreparedStatement ps = conn.prepareStatement("INSERT INTO " + TABLE_NAME + " VALUES (?, ?, ?, ?)")) {

			for (int i = 0; i < ROW_COUNT; i++) {
				ps.setInt(1, i);
				ps.setString(2, "LEID-" + i);
				ps.setString(3, "Name-" + i);
				ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
				if ((i + 1) % 10_000 == 0)
					System.out.printf("  %,d rows%n", i + 1);
			}
		}

		long elapsed = System.nanoTime() - start;
		System.gc();
		long heapDelta = usedHeap() - heapBefore;
		long dbRowsAfter = countConvexRows(schema);

		server.persistSnapshot(server.getLocalValue());
		long writtenBytes = etchDataBytes(store.getHotFile());
		long allocBytes   = store.getHotFile().length();
		long compBytes    = estimateCompressedSize(store.getHotFile());
		System.out.printf("  Elapsed: %,.0f ms  |  Written: %.1f MB  |  Alloc: %.1f MB  |  ~gz: %.1f MB  |  DB rows: %,d→%,d%n",
				elapsed / 1e6, writtenBytes / (1024.0 * 1024.0), allocBytes / (1024.0 * 1024.0), compBytes / (1024.0 * 1024.0),
				dbRowsBefore, dbRowsAfter);

		cdb.unregister(DB_NAME);
		server.close();
		store.close();
		return new BenchResult("Convex JDBC prepared", ROW_COUNT, elapsed, writtenBytes, allocBytes, compBytes, heapDelta, dbRowsBefore, dbRowsAfter);
	}

	static BenchResult benchConvexBatch() throws Exception {
		System.out.println("--- Convex JDBC PreparedStatement batch ---");
		File storeDir = new File(STORE_DIR, "cmp-convex-batch");
		SegmentedEtchStore store = SegmentedEtchStore.createFresh(storeDir);
		NodeServer<?> server = ConvexDB.createNodeServer(store);
		server.launch();
		ConvexDB cdb = ConvexDB.connect(server.getCursor());
		SQLDatabase db = cdb.database(DB_NAME);
		SQLSchema schema = db.tables();
		createConvexTable(schema);
		cdb.register(DB_NAME);

		long dbRowsBefore = countConvexRows(schema);
		long heapBefore = usedHeap();
		long start = System.nanoTime();

		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
				PreparedStatement ps = conn.prepareStatement("INSERT INTO " + TABLE_NAME + " VALUES (?, ?, ?, ?)")) {

			for (int i = 0; i < ROW_COUNT; i++) {
				ps.setInt(1, i);
				ps.setString(2, "LEID-" + i);
				ps.setString(3, "Name-" + i);
				ps.setLong(4, RND.nextLong());
				ps.addBatch();
				if ((i + 1) % BATCH_SIZE == 0) {
					ps.executeBatch();
					if ((i + 1) % 10_000 == 0)
						System.out.printf("  %,d rows%n", i + 1);
				}
			}
			// flush remaining
			ps.executeBatch();
		}

		long elapsed = System.nanoTime() - start;
		System.gc();
		long heapDelta = usedHeap() - heapBefore;
		long dbRowsAfter = countConvexRows(schema);

		server.persistSnapshot(server.getLocalValue());
		long writtenBytes = etchDataBytes(store.getHotFile());
		long allocBytes   = store.getHotFile().length();
		long compBytes    = estimateCompressedSize(store.getHotFile());
		System.out.printf("  Elapsed: %,.0f ms  |  Written: %.1f MB  |  Alloc: %.1f MB  |  ~gz: %.1f MB  |  DB rows: %,d→%,d%n",
				elapsed / 1e6, writtenBytes / (1024.0 * 1024.0), allocBytes / (1024.0 * 1024.0), compBytes / (1024.0 * 1024.0),
				dbRowsBefore, dbRowsAfter);

		cdb.unregister(DB_NAME);
		server.close();
		store.close();
		return new BenchResult("Convex JDBC batch", ROW_COUNT, elapsed, writtenBytes, allocBytes, compBytes, heapDelta, dbRowsBefore, dbRowsAfter);
	}

	// ── MariaDB benchmarks ─────────────────────────────────────────────────────

	static boolean checkMariaDB() {
		try {
			setupMariaDB();
			return true;
		} catch (Exception e) {
			System.out.println("  MariaDB setup failed: " + e.getMessage());
			return false;
		}
	}

	static void setupMariaDB() throws Exception {
		// Connect directly to the existing database — no CREATE DATABASE needed
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		try (Connection conn = DriverManager.getConnection(connUrl);
				Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("DROP TABLE IF EXISTS " + TABLE_NAME);
			stmt.executeUpdate(
				"CREATE TABLE " + TABLE_NAME + " ("
				+ "id BIGINT PRIMARY KEY, "
				+ "leid VARCHAR(32), "
				+ "nm VARCHAR(32), "
				+ "rnd BIGINT"
				+ ") ENGINE=InnoDB ROW_FORMAT=COMPRESSED");
		}
	}

	static BenchResult benchMariaDBPrepared() throws Exception {
		System.out.println("--- MariaDB JDBC PreparedStatement ---");
		setupMariaDB();

		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		long dbRowsBefore = countMariaDBRows();
		long heapBefore = usedHeap();
		long start = System.nanoTime();

		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON DUPLICATE KEY UPDATE leid=VALUES(leid), nm=VALUES(nm), rnd=VALUES(rnd)")) {

			for (int i = 0; i < ROW_COUNT; i++) {
				ps.setInt(1, i);
				ps.setString(2, "LEID-" + i);
				ps.setString(3, "Name-" + i);
				ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
				if ((i + 1) % 10_000 == 0)
					System.out.printf("  %,d rows%n", i + 1);
			}
		}

		long elapsed = System.nanoTime() - start;
		long heapDelta = usedHeap() - heapBefore;
		long dbRowsAfter = countMariaDBRows();
		long fileBytes = mariaDBTableBytes();
		System.out.printf("  Elapsed: %,.0f ms  |  Table size: %.1f MB (InnoDB compressed)  |  DB rows: %,d→%,d%n",
				elapsed / 1e6, fileBytes / (1024.0 * 1024.0), dbRowsBefore, dbRowsAfter);

		return new BenchResult("MariaDB JDBC prepared", ROW_COUNT, elapsed, fileBytes, fileBytes, fileBytes, heapDelta, dbRowsBefore, dbRowsAfter);
	}

	static BenchResult benchMariaDBBatch() throws Exception {
		System.out.println("--- MariaDB JDBC PreparedStatement batch ---");
		setupMariaDB();

		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		long dbRowsBefore = countMariaDBRows();
		long heapBefore = usedHeap();
		long start = System.nanoTime();

		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON DUPLICATE KEY UPDATE leid=VALUES(leid), nm=VALUES(nm), rnd=VALUES(rnd)")) {

			for (int i = 0; i < ROW_COUNT; i++) {
				ps.setInt(1, i);
				ps.setString(2, "LEID-" + i);
				ps.setString(3, "Name-" + i);
				ps.setLong(4, RND.nextLong());
				ps.addBatch();
				if ((i + 1) % BATCH_SIZE == 0) {
					ps.executeBatch();
					if ((i + 1) % 10_000 == 0)
						System.out.printf("  %,d rows%n", i + 1);
				}
			}
			ps.executeBatch();
		}

		long elapsed = System.nanoTime() - start;
		long heapDelta = usedHeap() - heapBefore;
		long dbRowsAfter = countMariaDBRows();
		long fileBytes = mariaDBTableBytes();
		System.out.printf("  Elapsed: %,.0f ms  |  Table size: %.1f MB (InnoDB compressed)  |  DB rows: %,d→%,d%n",
				elapsed / 1e6, fileBytes / (1024.0 * 1024.0), dbRowsBefore, dbRowsAfter);

		return new BenchResult("MariaDB JDBC batch", ROW_COUNT, elapsed, fileBytes, fileBytes, fileBytes, heapDelta, dbRowsBefore, dbRowsAfter);
	}

	static BenchResult benchMariaDBLoadFile() throws Exception {
		System.out.println("--- MariaDB LOAD DATA INFILE (CSV) ---");
		setupMariaDB();

		// Phase 1: write CSV file
		File csvFile = File.createTempFile("convex_bench_", ".csv");
		csvFile.deleteOnExit();
		long writeStart = System.nanoTime();
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(csvFile))) {
			for (int i = 0; i < ROW_COUNT; i++) {
				bw.write(i + "\tLEID-" + i + "\tName-" + i + "\t" + RND.nextLong() + "\n");
			}
		}
		long writeNs = System.nanoTime() - writeStart;
		System.out.printf("  CSV written: %s  (%.1f MB, %,.0f ms)%n",
				csvFile.getName(), csvFile.length() / (1024.0 * 1024.0), writeNs / 1e6);

		// Phase 2: LOAD DATA INFILE
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS
				+ "&allowLocalInfile=true";
		long dbRowsBefore = countMariaDBRows();
		long heapBefore = usedHeap();
		long loadStart = System.nanoTime();

		try (Connection conn = DriverManager.getConnection(connUrl);
				Statement stmt = conn.createStatement()) {
			stmt.execute(
				"LOAD DATA LOCAL INFILE '" + csvFile.getAbsolutePath().replace("\\", "/") + "' "
				+ "INTO TABLE " + TABLE_NAME + " "
				+ "FIELDS TERMINATED BY '\\t' "
				+ "LINES TERMINATED BY '\\n' "
				+ "(id, leid, nm, rnd)");
		}

		long loadNs = System.nanoTime() - loadStart;
		long totalNs = writeNs + loadNs;
		long heapDelta = usedHeap() - heapBefore;
		long dbRowsAfter = countMariaDBRows();
		long fileBytes = mariaDBTableBytes();
		System.out.printf("  Load: %,.0f ms  |  Total (write+load): %,.0f ms  |  Table: %.1f MB  |  DB rows: %,d→%,d%n",
				loadNs / 1e6, totalNs / 1e6, fileBytes / (1024.0 * 1024.0), dbRowsBefore, dbRowsAfter);

		csvFile.delete();
		// Report total elapsed (write + load) so comparison is apples-to-apples
		return new BenchResult("MariaDB LOAD FILE", ROW_COUNT, totalNs, fileBytes, fileBytes, fileBytes, heapDelta, dbRowsBefore, dbRowsAfter);
	}

	// ── PostgreSQL benchmarks ─────────────────────────────────────────────────

	static boolean checkPostgres() {
		try {
			setupPostgres();
			return true;
		} catch (Exception e) {
			System.out.println("  PostgreSQL setup failed: " + e.getMessage());
			return false;
		}
	}

	static void setupPostgres() throws Exception {
		try (Connection conn = DriverManager.getConnection(PSQL_URL, PSQL_USER, PSQL_PASS);
				Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("DROP TABLE IF EXISTS " + TABLE_NAME);
			stmt.executeUpdate(
				"CREATE TABLE " + TABLE_NAME + " ("
				+ "id BIGINT PRIMARY KEY, "
				+ "leid VARCHAR(32), "
				+ "nm VARCHAR(32), "
				+ "rnd BIGINT)");
		}
	}

	static long postgresTableBytes() throws Exception {
		try (Connection conn = DriverManager.getConnection(PSQL_URL, PSQL_USER, PSQL_PASS);
				Statement stmt = conn.createStatement()) {
			ResultSet rs = stmt.executeQuery("SELECT pg_total_relation_size('" + TABLE_NAME + "') AS b");
			if (rs.next()) return rs.getLong("b");
		}
		return -1;
	}

	static BenchResult benchPostgresPrepared() throws Exception {
		System.out.println("--- PostgreSQL JDBC PreparedStatement ---");
		setupPostgres();

		long dbRowsBefore = countPostgresRows();
		long heapBefore = usedHeap();
		long start = System.nanoTime();

		try (Connection conn = DriverManager.getConnection(PSQL_URL, PSQL_USER, PSQL_PASS);
				PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON CONFLICT (id) DO UPDATE SET leid=EXCLUDED.leid, nm=EXCLUDED.nm, rnd=EXCLUDED.rnd")) {

			for (int i = 0; i < ROW_COUNT; i++) {
				ps.setInt(1, i); ps.setString(2, "LEID-" + i); ps.setString(3, "Name-" + i); ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
				if ((i + 1) % 10_000 == 0) System.out.printf("  %,d rows%n", i + 1);
			}
		}

		long elapsed = System.nanoTime() - start;
		long heapDelta = usedHeap() - heapBefore;
		long dbRowsAfter = countPostgresRows();
		long fileBytes = postgresTableBytes();
		System.out.printf("  Elapsed: %,.0f ms  |  Table: %.1f MB  |  DB rows: %,d→%,d%n",
				elapsed / 1e6, fileBytes / (1024.0 * 1024.0), dbRowsBefore, dbRowsAfter);
		return new BenchResult("PostgreSQL prepared", ROW_COUNT, elapsed, fileBytes, fileBytes, -1, heapDelta, dbRowsBefore, dbRowsAfter);
	}

	static BenchResult benchPostgresBatch() throws Exception {
		System.out.println("--- PostgreSQL JDBC PreparedStatement batch ---");
		setupPostgres();

		long dbRowsBefore = countPostgresRows();
		long heapBefore = usedHeap();
		long start = System.nanoTime();

		try (Connection conn = DriverManager.getConnection(PSQL_URL, PSQL_USER, PSQL_PASS);
				PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON CONFLICT (id) DO UPDATE SET leid=EXCLUDED.leid, nm=EXCLUDED.nm, rnd=EXCLUDED.rnd")) {

			for (int i = 0; i < ROW_COUNT; i++) {
				ps.setInt(1, i); ps.setString(2, "LEID-" + i); ps.setString(3, "Name-" + i); ps.setLong(4, RND.nextLong());
				ps.addBatch();
				if ((i + 1) % BATCH_SIZE == 0) {
					ps.executeBatch();
					if ((i + 1) % 10_000 == 0) System.out.printf("  %,d rows%n", i + 1);
				}
			}
			ps.executeBatch();
		}

		long elapsed = System.nanoTime() - start;
		long heapDelta = usedHeap() - heapBefore;
		long dbRowsAfter = countPostgresRows();
		long fileBytes = postgresTableBytes();
		System.out.printf("  Elapsed: %,.0f ms  |  Table: %.1f MB  |  DB rows: %,d→%,d%n",
				elapsed / 1e6, fileBytes / (1024.0 * 1024.0), dbRowsBefore, dbRowsAfter);
		return new BenchResult("PostgreSQL batch", ROW_COUNT, elapsed, fileBytes, fileBytes, -1, heapDelta, dbRowsBefore, dbRowsAfter);
	}

	static BenchResult benchPostgresCopy() throws Exception {
		System.out.println("--- PostgreSQL COPY FROM STDIN (CSV) ---");
		setupPostgres();

		// Phase 1: write CSV file
		File csvFile = File.createTempFile("convex_psql_", ".csv");
		csvFile.deleteOnExit();
		long writeStart = System.nanoTime();
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(csvFile))) {
			for (int i = 0; i < ROW_COUNT; i++)
				bw.write(i + "\tLEID-" + i + "\tName-" + i + "\t" + RND.nextLong() + "\n");
		}
		long writeNs = System.nanoTime() - writeStart;
		System.out.printf("  CSV written: %.1f MB, %,.0f ms%n", csvFile.length() / (1024.0 * 1024.0), writeNs / 1e6);

		// Phase 2: COPY FROM STDIN via CopyManager
		long dbRowsBefore = countPostgresRows();
		long heapBefore = usedHeap();
		long copyStart = System.nanoTime();

		try (Connection conn = DriverManager.getConnection(PSQL_URL, PSQL_USER, PSQL_PASS);
				FileReader fr = new FileReader(csvFile)) {
			org.postgresql.copy.CopyManager cm =
				conn.unwrap(org.postgresql.PGConnection.class).getCopyAPI();
			cm.copyIn("COPY " + TABLE_NAME + " (id,leid,nm,rnd) FROM STDIN", fr);
		}

		long copyNs = System.nanoTime() - copyStart;
		long totalNs = writeNs + copyNs;
		long heapDelta = usedHeap() - heapBefore;
		long dbRowsAfter = countPostgresRows();
		long fileBytes = postgresTableBytes();
		System.out.printf("  COPY: %,.0f ms  |  Total: %,.0f ms  |  Table: %.1f MB  |  DB rows: %,d→%,d%n",
				copyNs / 1e6, totalNs / 1e6, fileBytes / (1024.0 * 1024.0), dbRowsBefore, dbRowsAfter);

		csvFile.delete();
		return new BenchResult("PostgreSQL COPY", ROW_COUNT, totalNs, fileBytes, fileBytes, -1, heapDelta, dbRowsBefore, dbRowsAfter);
	}

	static BenchResult benchPostgresPreparedSingle() throws Exception {
		System.out.println("--- Single-row: PostgreSQL JDBC PreparedStatement ---");
		setupPostgres();

		try (Connection conn = DriverManager.getConnection(PSQL_URL, PSQL_USER, PSQL_PASS);
				PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON CONFLICT (id) DO UPDATE SET leid=EXCLUDED.leid, nm=EXCLUDED.nm, rnd=EXCLUDED.rnd")) {

			for (int i = 0; i < SINGLE_WARMUP; i++) {
				ps.setInt(1, i); ps.setString(2, "LEID-" + i); ps.setString(3, "Name-" + i); ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
			}
			long start = System.nanoTime();
			for (int i = SINGLE_WARMUP; i < SINGLE_WARMUP + SINGLE_SAMPLES; i++) {
				ps.setInt(1, i); ps.setString(2, "LEID-" + i); ps.setString(3, "Name-" + i); ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
			}
			long elapsed = System.nanoTime() - start;
			System.out.printf("  Avg latency: %.1f µs%n", elapsed / 1000.0 / SINGLE_SAMPLES);
			return new BenchResult("PostgreSQL (1-row)", SINGLE_SAMPLES, elapsed, -1, -1, -1, 0);
		}
	}

	static List<BenchResult> benchPostgresRW() throws Exception {
		System.out.println("--- Read/Update: PostgreSQL ---");
		setupPostgres();

		// Load via batch
		try (Connection conn = DriverManager.getConnection(PSQL_URL, PSQL_USER, PSQL_PASS);
				PreparedStatement ins = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON CONFLICT (id) DO UPDATE SET leid=EXCLUDED.leid, nm=EXCLUDED.nm, rnd=EXCLUDED.rnd")) {
			for (int i = 0; i < ROW_COUNT; i++) {
				ins.setInt(1, i); ins.setString(2, "LEID-" + i); ins.setString(3, "Name-" + i); ins.setLong(4, RND.nextLong());
				ins.addBatch();
				if ((i + 1) % BATCH_SIZE == 0) ins.executeBatch();
			}
			ins.executeBatch();
		}
		System.out.printf("  Loaded %,d rows%n", ROW_COUNT);

		try (Connection conn = DriverManager.getConnection(PSQL_URL, PSQL_USER, PSQL_PASS);
				PreparedStatement sel = conn.prepareStatement("SELECT id,leid,nm,rnd FROM " + TABLE_NAME + " WHERE id=?");
				PreparedStatement upd = conn.prepareStatement("UPDATE " + TABLE_NAME + " SET leid=?,nm=?,rnd=? WHERE id=?")) {

			long t0 = System.nanoTime();
			for (int i = 0; i < RW_OPS; i++) {
				sel.setLong(1, Math.abs(RND.nextLong()) % ROW_COUNT);
				sel.executeQuery().close();
			}
			long lookupNs = System.nanoTime() - t0;
			System.out.printf("  Lookup avg: %.1f µs%n", lookupNs / 1000.0 / RW_OPS);

			long t1 = System.nanoTime();
			for (int i = 0; i < RW_OPS; i++) {
				long id = Math.abs(RND.nextLong()) % ROW_COUNT;
				upd.setString(1, "LEID-" + id); upd.setString(2, "Name-" + id); upd.setLong(3, RND.nextLong()); upd.setLong(4, id);
				upd.executeUpdate();
			}
			long updateNs = System.nanoTime() - t1;
			System.out.printf("  Update avg: %.1f µs%n", updateNs / 1000.0 / RW_OPS);

			return List.of(
				new BenchResult("PostgreSQL lookup", RW_OPS, lookupNs, -1, -1, -1, 0),
				new BenchResult("PostgreSQL update", RW_OPS, updateNs, -1, -1, -1, 0));
		}
	}

	// ── MariaDB system-versioned benchmarks ────────────────────────────────────

	static void setupMariaDBVersioned() throws Exception {
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		try (Connection conn = DriverManager.getConnection(connUrl);
				Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("DROP TABLE IF EXISTS " + TABLE_NAME);
			stmt.executeUpdate(
				"CREATE TABLE " + TABLE_NAME + " ("
				+ "id BIGINT PRIMARY KEY, "
				+ "leid VARCHAR(32), "
				+ "nm VARCHAR(32), "
				+ "rnd BIGINT"
				+ ") ENGINE=InnoDB ROW_FORMAT=COMPRESSED WITH SYSTEM VERSIONING");
		}
	}

	static BenchResult benchMariaDBVersionedPrepared() throws Exception {
		System.out.println("--- MariaDB versioned JDBC PreparedStatement ---");
		setupMariaDBVersioned();

		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		long dbRowsBefore = countMariaDBRows();
		long heapBefore = usedHeap();
		long start = System.nanoTime();

		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON DUPLICATE KEY UPDATE leid=VALUES(leid), nm=VALUES(nm), rnd=VALUES(rnd)")) {

			for (int i = 0; i < ROW_COUNT; i++) {
				ps.setInt(1, i);
				ps.setString(2, "LEID-" + i);
				ps.setString(3, "Name-" + i);
				ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
				if ((i + 1) % 10_000 == 0)
					System.out.printf("  %,d rows%n", i + 1);
			}
		}

		long elapsed = System.nanoTime() - start;
		long heapDelta = usedHeap() - heapBefore;
		long dbRowsAfter = countMariaDBRows();
		long fileBytes = mariaDBTableBytes();
		System.out.printf("  Elapsed: %,.0f ms  |  Table size: %.1f MB (InnoDB compressed+versioned)  |  DB rows: %,d→%,d%n",
				elapsed / 1e6, fileBytes / (1024.0 * 1024.0), dbRowsBefore, dbRowsAfter);

		return new BenchResult("MariaDB versioned prepared", ROW_COUNT, elapsed, fileBytes, fileBytes, fileBytes, heapDelta, dbRowsBefore, dbRowsAfter);
	}

	static BenchResult benchMariaDBVersionedBatch() throws Exception {
		System.out.println("--- MariaDB versioned JDBC PreparedStatement batch ---");
		setupMariaDBVersioned();

		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		long dbRowsBefore = countMariaDBRows();
		long heapBefore = usedHeap();
		long start = System.nanoTime();

		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON DUPLICATE KEY UPDATE leid=VALUES(leid), nm=VALUES(nm), rnd=VALUES(rnd)")) {

			for (int i = 0; i < ROW_COUNT; i++) {
				ps.setInt(1, i);
				ps.setString(2, "LEID-" + i);
				ps.setString(3, "Name-" + i);
				ps.setLong(4, RND.nextLong());
				ps.addBatch();
				if ((i + 1) % BATCH_SIZE == 0) {
					ps.executeBatch();
					if ((i + 1) % 10_000 == 0)
						System.out.printf("  %,d rows%n", i + 1);
				}
			}
			ps.executeBatch();
		}

		long elapsed = System.nanoTime() - start;
		long heapDelta = usedHeap() - heapBefore;
		long dbRowsAfter = countMariaDBRows();
		long fileBytes = mariaDBTableBytes();
		System.out.printf("  Elapsed: %,.0f ms  |  Table size: %.1f MB (InnoDB compressed+versioned)  |  DB rows: %,d→%,d%n",
				elapsed / 1e6, fileBytes / (1024.0 * 1024.0), dbRowsBefore, dbRowsAfter);

		return new BenchResult("MariaDB versioned batch", ROW_COUNT, elapsed, fileBytes, fileBytes, fileBytes, heapDelta, dbRowsBefore, dbRowsAfter);
	}

	static BenchResult benchMariaDBVersionedLoadFile() throws Exception {
		System.out.println("--- MariaDB versioned LOAD DATA INFILE (CSV) ---");
		setupMariaDBVersioned();

		File csvFile = File.createTempFile("convex_bench_ver_", ".csv");
		csvFile.deleteOnExit();
		long writeStart = System.nanoTime();
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(csvFile))) {
			for (int i = 0; i < ROW_COUNT; i++) {
				bw.write(i + "\tLEID-" + i + "\tName-" + i + "\t" + RND.nextLong() + "\n");
			}
		}
		long writeNs = System.nanoTime() - writeStart;
		System.out.printf("  CSV written: %.1f MB, %,.0f ms%n",
				csvFile.length() / (1024.0 * 1024.0), writeNs / 1e6);

		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS
				+ "&allowLocalInfile=true";
		long dbRowsBefore = countMariaDBRows();
		long heapBefore = usedHeap();
		long loadStart = System.nanoTime();

		try (Connection conn = DriverManager.getConnection(connUrl);
				Statement stmt = conn.createStatement()) {
			stmt.execute(
				"LOAD DATA LOCAL INFILE '" + csvFile.getAbsolutePath().replace("\\", "/") + "' "
				+ "INTO TABLE " + TABLE_NAME + " "
				+ "FIELDS TERMINATED BY '\\t' "
				+ "LINES TERMINATED BY '\\n' "
				+ "(id, leid, nm, rnd)");
		}

		long loadNs = System.nanoTime() - loadStart;
		long totalNs = writeNs + loadNs;
		long heapDelta = usedHeap() - heapBefore;
		long dbRowsAfter = countMariaDBRows();
		long fileBytes = mariaDBTableBytes();
		System.out.printf("  Load: %,.0f ms  |  Total: %,.0f ms  |  Table: %.1f MB  |  DB rows: %,d→%,d%n",
				loadNs / 1e6, totalNs / 1e6, fileBytes / (1024.0 * 1024.0), dbRowsBefore, dbRowsAfter);

		csvFile.delete();
		return new BenchResult("MariaDB versioned LOAD FILE", ROW_COUNT, totalNs, fileBytes, fileBytes, fileBytes, heapDelta, dbRowsBefore, dbRowsAfter);
	}

	// ── Single-row latency benchmarks ─────────────────────────────────────────

	static BenchResult benchConvexDirectSingle() throws Exception {
		System.out.println("--- Single-row: Convex direct (plain) ---");
		// Plain SQLSchema — no history tracking, matches JDBC benchmark for fair comparison
		SQLSchema schema = SQLSchema.create();
		createConvexTable(schema);

		// Warmup
		for (int i = 0; i < SINGLE_WARMUP; i++)
			schema.insert(TABLE_NAME, Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i, CVMLong.create(RND.nextLong())));

		long start = System.nanoTime();
		for (int i = SINGLE_WARMUP; i < SINGLE_WARMUP + SINGLE_SAMPLES; i++)
			schema.insert(TABLE_NAME, Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i, CVMLong.create(RND.nextLong())));
		long elapsed = System.nanoTime() - start;

		System.out.printf("  Avg latency: %.1f µs%n", elapsed / 1000.0 / SINGLE_SAMPLES);
		return new BenchResult("Convex direct (1-row)", SINGLE_SAMPLES, elapsed, -1, -1, -1, 0);
	}

	static BenchResult benchConvexPreparedSingle() throws Exception {
		System.out.println("--- Single-row: Convex JDBC PreparedStatement ---");
		File storeDir = new File(STORE_DIR, "cmp-single-convex");
		SegmentedEtchStore store = SegmentedEtchStore.createFresh(storeDir);
		NodeServer<?> server = ConvexDB.createNodeServer(store);
		server.launch();
		ConvexDB cdb = ConvexDB.connect(server.getCursor());
		SQLDatabase db = cdb.database(DB_NAME);
		SQLSchema schema = db.tables();
		createConvexTable(schema);
		cdb.register(DB_NAME);

		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
				PreparedStatement ps = conn.prepareStatement("INSERT INTO " + TABLE_NAME + " VALUES (?, ?, ?, ?)")) {

			// Warmup
			for (int i = 0; i < SINGLE_WARMUP; i++) {
				ps.setInt(1, i); ps.setString(2, "LEID-" + i); ps.setString(3, "Name-" + i); ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
			}

			long start = System.nanoTime();
			for (int i = SINGLE_WARMUP; i < SINGLE_WARMUP + SINGLE_SAMPLES; i++) {
				ps.setInt(1, i); ps.setString(2, "LEID-" + i); ps.setString(3, "Name-" + i); ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
			}
			long elapsed = System.nanoTime() - start;

			System.out.printf("  Avg latency: %.1f µs%n", elapsed / 1000.0 / SINGLE_SAMPLES);
			cdb.unregister(DB_NAME);
			server.close();
			store.close();
			return new BenchResult("Convex JDBC (1-row)", SINGLE_SAMPLES, elapsed, -1, -1, -1, 0);
		}
	}

	static BenchResult benchMariaDBPreparedSingle() throws Exception {
		System.out.println("--- Single-row: MariaDB JDBC PreparedStatement ---");
		setupMariaDB();
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;

		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON DUPLICATE KEY UPDATE leid=VALUES(leid), nm=VALUES(nm), rnd=VALUES(rnd)")) {

			// Warmup
			for (int i = 0; i < SINGLE_WARMUP; i++) {
				ps.setInt(1, i); ps.setString(2, "LEID-" + i); ps.setString(3, "Name-" + i); ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
			}

			long start = System.nanoTime();
			for (int i = SINGLE_WARMUP; i < SINGLE_WARMUP + SINGLE_SAMPLES; i++) {
				ps.setInt(1, i); ps.setString(2, "LEID-" + i); ps.setString(3, "Name-" + i); ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
			}
			long elapsed = System.nanoTime() - start;

			System.out.printf("  Avg latency: %.1f µs%n", elapsed / 1000.0 / SINGLE_SAMPLES);
			return new BenchResult("MariaDB JDBC (1-row)", SINGLE_SAMPLES, elapsed, -1, -1, -1, 0);
		}
	}

	static BenchResult benchMariaDBLoadFileSingle() throws Exception {
		System.out.println("--- Single-row: MariaDB LOAD DATA INFILE ---");
		setupMariaDB();
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS
				+ "&allowLocalInfile=true";
		File csvFile = File.createTempFile("convex_single_", ".csv");
		csvFile.deleteOnExit();

		// Warmup
		for (int i = 0; i < SINGLE_WARMUP; i++) {
			try (BufferedWriter bw = new BufferedWriter(new FileWriter(csvFile))) {
				bw.write(i + "\tLEID-" + i + "\tName-" + i + "\t" + RND.nextLong() + "\n");
			}
			try (Connection conn = DriverManager.getConnection(connUrl); Statement stmt = conn.createStatement()) {
				stmt.execute("LOAD DATA LOCAL INFILE '" + csvFile.getAbsolutePath().replace("\\", "/") + "' "
					+ "REPLACE INTO TABLE " + TABLE_NAME + " FIELDS TERMINATED BY '\\t' LINES TERMINATED BY '\\n' (id,leid,nm,rnd)");
			}
		}

		long start = System.nanoTime();
		for (int i = SINGLE_WARMUP; i < SINGLE_WARMUP + SINGLE_SAMPLES; i++) {
			try (BufferedWriter bw = new BufferedWriter(new FileWriter(csvFile))) {
				bw.write(i + "\tLEID-" + i + "\tName-" + i + "\t" + RND.nextLong() + "\n");
			}
			try (Connection conn = DriverManager.getConnection(connUrl); Statement stmt = conn.createStatement()) {
				stmt.execute("LOAD DATA LOCAL INFILE '" + csvFile.getAbsolutePath().replace("\\", "/") + "' "
					+ "REPLACE INTO TABLE " + TABLE_NAME + " FIELDS TERMINATED BY '\\t' LINES TERMINATED BY '\\n' (id,leid,nm,rnd)");
			}
		}
		long elapsed = System.nanoTime() - start;

		System.out.printf("  Avg latency: %.1f µs%n", elapsed / 1000.0 / SINGLE_SAMPLES);
		csvFile.delete();
		return new BenchResult("MariaDB LOAD FILE (1-row)", SINGLE_SAMPLES, elapsed, -1, -1, -1, 0);
	}

	static BenchResult benchMariaDBVersionedPreparedSingle() throws Exception {
		System.out.println("--- Single-row: MariaDB versioned JDBC PreparedStatement ---");
		setupMariaDBVersioned();
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;

		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON DUPLICATE KEY UPDATE leid=VALUES(leid), nm=VALUES(nm), rnd=VALUES(rnd)")) {

			// Warmup
			for (int i = 0; i < SINGLE_WARMUP; i++) {
				ps.setInt(1, i); ps.setString(2, "LEID-" + i); ps.setString(3, "Name-" + i); ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
			}

			long start = System.nanoTime();
			for (int i = SINGLE_WARMUP; i < SINGLE_WARMUP + SINGLE_SAMPLES; i++) {
				ps.setInt(1, i); ps.setString(2, "LEID-" + i); ps.setString(3, "Name-" + i); ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
			}
			long elapsed = System.nanoTime() - start;

			System.out.printf("  Avg latency: %.1f µs%n", elapsed / 1000.0 / SINGLE_SAMPLES);
			return new BenchResult("MariaDB versioned (1-row)", SINGLE_SAMPLES, elapsed, -1, -1, -1, 0);
		}
	}

	// ── Read / Update benchmarks ──────────────────────────────────────────────

	/** Convex direct: random point lookups then random updates, no JDBC overhead. */
	static List<BenchResult> benchConvexDirectRW() throws Exception {
		System.out.println("--- Read/Update: Convex direct (plain) ---");
		SQLSchema schema = SQLSchema.create();  // plain — matches JDBC RW benchmarks
		createConvexTable(schema);

		// Load dataset
		for (int i = 0; i < ROW_COUNT; i++)
			schema.insert(TABLE_NAME, Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i, CVMLong.create(RND.nextLong())));
		System.out.printf("  Loaded %,d rows%n", ROW_COUNT);

		// Lookups
		long t0 = System.nanoTime();
		for (int i = 0; i < RW_OPS; i++) {
			long id = Math.abs(RND.nextLong()) % ROW_COUNT;
			schema.selectByKey(TABLE_NAME, CVMLong.create(id));
		}
		long lookupNs = System.nanoTime() - t0;
		System.out.printf("  Lookup avg: %.1f µs%n", lookupNs / 1000.0 / RW_OPS);

		// Updates
		long t1 = System.nanoTime();
		for (int i = 0; i < RW_OPS; i++) {
			long id = Math.abs(RND.nextLong()) % ROW_COUNT;
			schema.insert(TABLE_NAME, Vectors.of(CVMLong.create(id), "LEID-" + id, "Name-" + id, CVMLong.create(RND.nextLong())));
		}
		long updateNs = System.nanoTime() - t1;
		System.out.printf("  Update avg: %.1f µs%n", updateNs / 1000.0 / RW_OPS);

		return List.of(
			new BenchResult("Convex direct lookup", RW_OPS, lookupNs, -1, -1, -1, 0),
			new BenchResult("Convex direct update", RW_OPS, updateNs, -1, -1, -1, 0)
		);
	}

	/** Convex JDBC: random SELECT by PK and UPDATE via PreparedStatement. */
	static List<BenchResult> benchConvexJdbcRW() throws Exception {
		System.out.println("--- Read/Update: Convex JDBC ---");
		File storeDir = new File(STORE_DIR, "cmp-rw-convex");
		SegmentedEtchStore store = SegmentedEtchStore.createFresh(storeDir);
		NodeServer<?> server = ConvexDB.createNodeServer(store);
		server.launch();
		ConvexDB cdb = ConvexDB.connect(server.getCursor());
		SQLDatabase db = cdb.database(DB_NAME);
		SQLSchema schema = db.tables();
		createConvexTable(schema);
		cdb.register(DB_NAME);

		// Load dataset via lattice (faster setup)
		for (int i = 0; i < ROW_COUNT; i++)
			schema.insert(TABLE_NAME, Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i, CVMLong.create(RND.nextLong())));
		System.out.printf("  Loaded %,d rows%n", ROW_COUNT);

		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
				PreparedStatement sel = conn.prepareStatement("SELECT ID, LEID, NM, RND FROM " + TABLE_NAME + " WHERE ID = ?");
				PreparedStatement upd = conn.prepareStatement("INSERT INTO " + TABLE_NAME + " VALUES (?, ?, ?, ?)")) {

			// Lookups
			long t0 = System.nanoTime();
			for (int i = 0; i < RW_OPS; i++) {
				sel.setLong(1, Math.abs(RND.nextLong()) % ROW_COUNT);
				sel.executeQuery().close();
			}
			long lookupNs = System.nanoTime() - t0;
			System.out.printf("  Lookup avg: %.1f µs%n", lookupNs / 1000.0 / RW_OPS);

			// Updates
			long t1 = System.nanoTime();
			for (int i = 0; i < RW_OPS; i++) {
				long id = Math.abs(RND.nextLong()) % ROW_COUNT;
				upd.setLong(1, id); upd.setString(2, "LEID-" + id); upd.setString(3, "Name-" + id); upd.setLong(4, RND.nextLong());
				upd.executeUpdate();
			}
			long updateNs = System.nanoTime() - t1;
			System.out.printf("  Update avg: %.1f µs%n", updateNs / 1000.0 / RW_OPS);

			cdb.unregister(DB_NAME);
			server.close();
			store.close();
			return List.of(
				new BenchResult("Convex JDBC lookup", RW_OPS, lookupNs, -1, -1, -1, 0),
				new BenchResult("Convex JDBC update", RW_OPS, updateNs, -1, -1, -1, 0)
			);
		}
	}

	/** MariaDB: random SELECT by PK and UPDATE via PreparedStatement. */
	static List<BenchResult> benchMariaDBRW(boolean versioned) throws Exception {
		String tag = versioned ? "versioned" : "plain";
		System.out.println("--- Read/Update: MariaDB " + tag + " ---");
		if (versioned) setupMariaDBVersioned(); else setupMariaDB();

		// Load dataset via batch
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement ins = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON DUPLICATE KEY UPDATE leid=VALUES(leid), nm=VALUES(nm), rnd=VALUES(rnd)")) {
			for (int i = 0; i < ROW_COUNT; i++) {
				ins.setInt(1, i); ins.setString(2, "LEID-" + i); ins.setString(3, "Name-" + i); ins.setLong(4, RND.nextLong());
				ins.addBatch();
				if ((i + 1) % BATCH_SIZE == 0) ins.executeBatch();
			}
			ins.executeBatch();
		}
		System.out.printf("  Loaded %,d rows%n", ROW_COUNT);

		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement sel = conn.prepareStatement("SELECT id, leid, nm, rnd FROM " + TABLE_NAME + " WHERE id = ?");
				PreparedStatement upd = conn.prepareStatement(
					"UPDATE " + TABLE_NAME + " SET leid=?, nm=?, rnd=? WHERE id=?")) {

			// Lookups
			long t0 = System.nanoTime();
			for (int i = 0; i < RW_OPS; i++) {
				sel.setLong(1, Math.abs(RND.nextLong()) % ROW_COUNT);
				sel.executeQuery().close();
			}
			long lookupNs = System.nanoTime() - t0;
			System.out.printf("  Lookup avg: %.1f µs%n", lookupNs / 1000.0 / RW_OPS);

			// Updates
			long t1 = System.nanoTime();
			for (int i = 0; i < RW_OPS; i++) {
				long id = Math.abs(RND.nextLong()) % ROW_COUNT;
				upd.setString(1, "LEID-" + id); upd.setString(2, "Name-" + id); upd.setLong(3, RND.nextLong()); upd.setLong(4, id);
				upd.executeUpdate();
			}
			long updateNs = System.nanoTime() - t1;
			System.out.printf("  Update avg: %.1f µs%n", updateNs / 1000.0 / RW_OPS);

			return List.of(
				new BenchResult("MariaDB " + tag + " lookup", RW_OPS, lookupNs, -1, -1, -1, 0),
				new BenchResult("MariaDB " + tag + " update", RW_OPS, updateNs, -1, -1, -1, 0)
			);
		}
	}

	/**
	 * Full table scan benchmark: measures heap consumed while iterating SELECT * on ROW_COUNT rows.
	 * The heap spike between executeQuery() and first rs.next() reveals pre-collection overhead.
	 * With the streaming enumerator this should be near zero; with the old list-based one it is O(n).
	 */
	static BenchResult benchConvexJdbcScan() throws Exception {
		System.out.println("--- Full scan: Convex JDBC SELECT * ---");
		File storeDir = new File(STORE_DIR, "cmp-scan-convex");
		SegmentedEtchStore store = SegmentedEtchStore.createFresh(storeDir);
		NodeServer<?> server = ConvexDB.createNodeServer(store);
		server.launch();
		ConvexDB cdb = ConvexDB.connect(server.getCursor());
		SQLDatabase db = cdb.database(DB_NAME);
		SQLSchema schema = db.tables();
		createConvexTable(schema);
		cdb.register(DB_NAME);

		// Use batch insert so blockVec is built inline and persisted to Etch.
		// After gc() the scan can load the compact VectorTree instead of the full Index trie.
		List<AVector<ACell>> scanBatch = new ArrayList<>(ROW_COUNT);
		for (int i = 0; i < ROW_COUNT; i++)
			scanBatch.add(Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i, CVMLong.create(RND.nextLong())));
		schema.insertAll(TABLE_NAME, scanBatch);
		scanBatch = null; // allow GC of input list
		System.out.printf("  Loaded %,d rows%n", ROW_COUNT);

		System.gc(); Thread.sleep(100); System.gc();
		long heapBefore = usedHeap();
		long start = System.nanoTime();
		long heapPeak = heapBefore;
		int count = 0;

		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME);
			 Statement stmt = conn.createStatement()) {
			ResultSet rs = stmt.executeQuery("SELECT * FROM " + TABLE_NAME);
			// Track peak heap during full scan (lazy scan: rows decoded on rs.next())
			while (rs.next()) {
				count++;
				if ((count & 0xFFF) == 0) heapPeak = Math.max(heapPeak, usedHeap());
			}
			heapPeak = Math.max(heapPeak, usedHeap());
			rs.close();
		}

		long elapsed = System.nanoTime() - start;
		long heapDelta = heapPeak - heapBefore;
		System.out.printf("  Rows: %,d  |  Elapsed: %,.0f ms  |  Peak heap: +%.1f MB%n",
				count, elapsed / 1e6, heapDelta / (1024.0 * 1024.0));

		cdb.unregister(DB_NAME);
		server.close();
		store.close();
		return new BenchResult("Convex JDBC scan", ROW_COUNT, elapsed, -1, -1, -1, heapDelta);
	}

	// ── Merge latency benchmark ───────────────────────────────────────────────

	/**
	 * Times in-JVM lattice merge: full (empty→ROW_COUNT) then incremental (k changes).
	 * Verifies convergence after each merge (A's value == B's value).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	static List<BenchResult> benchConvexMergeLatency() throws Exception {
		System.out.println("--- Convex merge latency (in-JVM: full + incremental) ---");
		List<BenchResult> results = new ArrayList<>();

		// ── Build base on nodeA ──────────────────────────────────────────
		File dirA = new File(STORE_DIR, "cmp-merge-nodeA");
		SegmentedEtchStore storeA = SegmentedEtchStore.createFresh(dirA);
		NodeServer serverA = ConvexDB.createNodeServer(storeA);
		serverA.launch();
		SQLDatabase dbA = SQLDatabase.connect(serverA.getCursor(), DB_NAME);
		SQLSchema schemaA = dbA.tables();
		createConvexTable(schemaA);

		List<AVector<ACell>> batch = new ArrayList<>(ROW_COUNT);
		for (int i = 0; i < ROW_COUNT; i++)
			batch.add(Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i, CVMLong.create(RND.nextLong())));
		schemaA.insertAll(TABLE_NAME, batch);
		System.out.printf("  NodeA loaded: %,d rows%n", ROW_COUNT);

		// ── NodeB: empty ─────────────────────────────────────────────────
		File dirB = new File(STORE_DIR, "cmp-merge-nodeB");
		SegmentedEtchStore storeB = SegmentedEtchStore.createFresh(dirB);
		NodeServer serverB = ConvexDB.createNodeServer(storeB);
		serverB.launch();

		// ── Full merge: A (ROW_COUNT rows) → B (empty) ───────────────────
		System.gc(); Thread.sleep(50);
		long fullStart = System.nanoTime();
		serverB.mergeValue(serverA.getLocalValue());
		long fullNs = System.nanoTime() - fullStart;

		boolean fullConverged = serverA.getLocalValue().equals(serverB.getLocalValue());
		System.out.printf("  Full merge (%,d rows): %,.0f ms  converged=%b%n",
				ROW_COUNT, fullNs / 1e6, fullConverged);
		results.add(new BenchResult("Convex merge full", ROW_COUNT, fullNs, -1, -1, -1, 0));

		// ── Incremental merges: apply k changes to A, merge to B ─────────
		for (int k : REPLICATION_K) {
			for (int i = 0; i < k; i++) {
				long id = Math.abs(RND.nextLong()) % ROW_COUNT;
				schemaA.insert(TABLE_NAME, Vectors.of(CVMLong.create(id),
						"UPD-" + id, "Name-" + id, CVMLong.create(RND.nextLong())));
			}
			System.gc(); Thread.sleep(50);
			long incrStart = System.nanoTime();
			serverB.mergeValue(serverA.getLocalValue());
			long incrNs = System.nanoTime() - incrStart;

			boolean incrConverged = serverA.getLocalValue().equals(serverB.getLocalValue());
			System.out.printf("  Incremental k=%,6d: %,.0f ms  converged=%b%n",
					k, incrNs / 1e6, incrConverged);
			results.add(new BenchResult("Convex merge k=" + k, k, incrNs, -1, -1, -1, 0));
		}

		serverA.close(); storeA.close();
		serverB.close(); storeB.close();
		return results;
	}

	static BenchResult benchMariaDBScan() throws Exception {
		System.out.println("--- Full scan: MariaDB SELECT * ---");
		setupMariaDB();
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		try (Connection conn = DriverManager.getConnection(connUrl);
			 PreparedStatement ins = conn.prepareStatement(
				 "INSERT INTO " + TABLE_NAME + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
				 + "ON DUPLICATE KEY UPDATE leid=VALUES(leid), nm=VALUES(nm), rnd=VALUES(rnd)")) {
			for (int i = 0; i < ROW_COUNT; i++) {
				ins.setInt(1, i); ins.setString(2, "LEID-" + i); ins.setString(3, "Name-" + i); ins.setLong(4, RND.nextLong());
				ins.addBatch();
				if ((i + 1) % BATCH_SIZE == 0) ins.executeBatch();
			}
			ins.executeBatch();
		}
		System.out.printf("  Loaded %,d rows%n", ROW_COUNT);

		System.gc(); Thread.sleep(100); System.gc();
		long heapBefore = usedHeap();
		long start = System.nanoTime();
		long heapPeak = heapBefore;
		int count = 0;

		try (Connection conn = DriverManager.getConnection(connUrl);
			 Statement stmt = conn.createStatement()) {
			ResultSet rs = stmt.executeQuery("SELECT * FROM " + TABLE_NAME);
			while (rs.next()) {
				count++;
				if ((count & 0xFFF) == 0) heapPeak = Math.max(heapPeak, usedHeap());
			}
			heapPeak = Math.max(heapPeak, usedHeap());
			rs.close();
		}

		long elapsed = System.nanoTime() - start;
		long heapDelta = heapPeak - heapBefore;
		System.out.printf("  Rows: %,d  |  Elapsed: %,.0f ms  |  Peak heap: +%.1f MB%n",
				count, elapsed / 1e6, heapDelta / (1024.0 * 1024.0));
		return new BenchResult("MariaDB scan", ROW_COUNT, elapsed, -1, -1, -1, heapDelta);
	}

	// ── MariaDB MEMORY engine benchmarks ─────────────────────────────────────

	static void setupMariaDBMemory() throws Exception {
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		try (Connection conn = DriverManager.getConnection(connUrl);
				Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("DROP TABLE IF EXISTS " + TABLE_NAME_MEM);
			stmt.executeUpdate(
				"CREATE TABLE " + TABLE_NAME_MEM + " ("
				+ "id BIGINT PRIMARY KEY, "
				+ "leid VARCHAR(32), "
				+ "nm VARCHAR(32), "
				+ "rnd BIGINT"
				+ ") ENGINE=MEMORY");
		}
	}

	static long countMariaDBMemoryRows() {
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		try (Connection conn = DriverManager.getConnection(connUrl);
				Statement stmt = conn.createStatement()) {
			ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + TABLE_NAME_MEM);
			if (rs.next()) return rs.getLong(1);
		} catch (Exception e) { /* table may not exist */ }
		return -1;
	}

	static BenchResult benchMariaDBMemoryPrepared() throws Exception {
		System.out.println("--- MariaDB MEMORY JDBC PreparedStatement ---");
		setupMariaDBMemory();
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		long rowsBefore = countMariaDBMemoryRows();
		long heapBefore = usedHeap();
		long start = System.nanoTime();

		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME_MEM + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON DUPLICATE KEY UPDATE leid=VALUES(leid), nm=VALUES(nm), rnd=VALUES(rnd)")) {
			for (int i = 0; i < ROW_COUNT; i++) {
				ps.setInt(1, i); ps.setString(2, "LEID-" + i);
				ps.setString(3, "Name-" + i); ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
				if ((i + 1) % 10_000 == 0) System.out.printf("  %,d rows%n", i + 1);
			}
		}

		long elapsed = System.nanoTime() - start;
		long heapDelta = usedHeap() - heapBefore;
		long rowsAfter = countMariaDBMemoryRows();
		System.out.printf("  Elapsed: %,.0f ms  |  Engine: MEMORY (no disk)  |  DB rows: %,d→%,d%n",
				elapsed / 1e6, rowsBefore, rowsAfter);
		return new BenchResult("MariaDB MEMORY prepared", ROW_COUNT, elapsed, -1, -1, -1, heapDelta, rowsBefore, rowsAfter);
	}

	static BenchResult benchMariaDBMemoryBatch() throws Exception {
		System.out.println("--- MariaDB MEMORY JDBC PreparedStatement batch ---");
		setupMariaDBMemory();
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		long rowsBefore = countMariaDBMemoryRows();
		long heapBefore = usedHeap();
		long start = System.nanoTime();

		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME_MEM + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON DUPLICATE KEY UPDATE leid=VALUES(leid), nm=VALUES(nm), rnd=VALUES(rnd)")) {
			for (int i = 0; i < ROW_COUNT; i++) {
				ps.setInt(1, i); ps.setString(2, "LEID-" + i);
				ps.setString(3, "Name-" + i); ps.setLong(4, RND.nextLong());
				ps.addBatch();
				if ((i + 1) % BATCH_SIZE == 0) {
					ps.executeBatch();
					if ((i + 1) % 10_000 == 0) System.out.printf("  %,d rows%n", i + 1);
				}
			}
			ps.executeBatch();
		}

		long elapsed = System.nanoTime() - start;
		long heapDelta = usedHeap() - heapBefore;
		long rowsAfter = countMariaDBMemoryRows();
		System.out.printf("  Elapsed: %,.0f ms  |  Engine: MEMORY (no disk)  |  DB rows: %,d→%,d%n",
				elapsed / 1e6, rowsBefore, rowsAfter);
		return new BenchResult("MariaDB MEMORY batch", ROW_COUNT, elapsed, -1, -1, -1, heapDelta, rowsBefore, rowsAfter);
	}

	static BenchResult benchMariaDBMemoryPreparedSingle() throws Exception {
		System.out.println("--- Single-row latency: MariaDB MEMORY ---");
		setupMariaDBMemory();
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;

		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME_MEM + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON DUPLICATE KEY UPDATE leid=VALUES(leid), nm=VALUES(nm), rnd=VALUES(rnd)")) {
			// Warmup
			for (int i = 0; i < SINGLE_WARMUP; i++) {
				ps.setInt(1, i); ps.setString(2, "LEID-" + i);
				ps.setString(3, "Name-" + i); ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
			}
			// Timed
			long start = System.nanoTime();
			for (int i = 0; i < SINGLE_SAMPLES; i++) {
				int id = SINGLE_WARMUP + i;
				ps.setInt(1, id); ps.setString(2, "LEID-" + id);
				ps.setString(3, "Name-" + id); ps.setLong(4, RND.nextLong());
				ps.executeUpdate();
			}
			long elapsed = System.nanoTime() - start;
			double usPerRow = elapsed / 1000.0 / SINGLE_SAMPLES;
			System.out.printf("  Avg: %.1f µs/row  (%,.0f rows/sec)%n",
					usPerRow, 1_000_000.0 / usPerRow);
			return new BenchResult("MariaDB MEMORY single", SINGLE_SAMPLES, elapsed, -1, -1, -1, 0);
		}
	}

	static List<BenchResult> benchMariaDBMemoryRW() throws Exception {
		System.out.println("--- Read/Update: MariaDB MEMORY ---");
		setupMariaDBMemory();
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;

		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement ins = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME_MEM + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON DUPLICATE KEY UPDATE leid=VALUES(leid), nm=VALUES(nm), rnd=VALUES(rnd)")) {
			for (int i = 0; i < ROW_COUNT; i++) {
				ins.setInt(1, i); ins.setString(2, "LEID-" + i);
				ins.setString(3, "Name-" + i); ins.setLong(4, RND.nextLong());
				ins.addBatch();
				if ((i + 1) % BATCH_SIZE == 0) ins.executeBatch();
			}
			ins.executeBatch();
		}
		System.out.printf("  Loaded %,d rows%n", ROW_COUNT);

		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement sel = conn.prepareStatement(
					"SELECT id,leid,nm,rnd FROM " + TABLE_NAME_MEM + " WHERE id=?");
				PreparedStatement upd = conn.prepareStatement(
					"UPDATE " + TABLE_NAME_MEM + " SET leid=?,nm=?,rnd=? WHERE id=?")) {

			long t0 = System.nanoTime();
			for (int i = 0; i < RW_OPS; i++) {
				sel.setLong(1, Math.abs(RND.nextLong()) % ROW_COUNT);
				sel.executeQuery().close();
			}
			long lookupNs = System.nanoTime() - t0;
			System.out.printf("  Lookup avg: %.1f µs%n", lookupNs / 1000.0 / RW_OPS);

			long t1 = System.nanoTime();
			for (int i = 0; i < RW_OPS; i++) {
				long id = Math.abs(RND.nextLong()) % ROW_COUNT;
				upd.setString(1, "LEID-" + id); upd.setString(2, "Name-" + id);
				upd.setLong(3, RND.nextLong()); upd.setLong(4, id);
				upd.executeUpdate();
			}
			long updateNs = System.nanoTime() - t1;
			System.out.printf("  Update avg: %.1f µs%n", updateNs / 1000.0 / RW_OPS);

			return List.of(
				new BenchResult("MariaDB MEMORY lookup", RW_OPS, lookupNs, -1, -1, -1, 0),
				new BenchResult("MariaDB MEMORY update", RW_OPS, updateNs, -1, -1, -1, 0)
			);
		}
	}

	static BenchResult benchMariaDBMemoryScan() throws Exception {
		System.out.println("--- Full scan: MariaDB MEMORY SELECT * ---");
		setupMariaDBMemory();
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;

		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement ins = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME_MEM + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON DUPLICATE KEY UPDATE leid=VALUES(leid), nm=VALUES(nm), rnd=VALUES(rnd)")) {
			for (int i = 0; i < ROW_COUNT; i++) {
				ins.setInt(1, i); ins.setString(2, "LEID-" + i);
				ins.setString(3, "Name-" + i); ins.setLong(4, RND.nextLong());
				ins.addBatch();
				if ((i + 1) % BATCH_SIZE == 0) ins.executeBatch();
			}
			ins.executeBatch();
		}
		System.out.printf("  Loaded %,d rows%n", ROW_COUNT);

		System.gc(); Thread.sleep(100); System.gc();
		long heapBefore = usedHeap();
		long start = System.nanoTime();
		long heapPeak = heapBefore;
		int count = 0;

		try (Connection conn = DriverManager.getConnection(connUrl);
				Statement stmt = conn.createStatement()) {
			ResultSet rs = stmt.executeQuery("SELECT * FROM " + TABLE_NAME_MEM);
			while (rs.next()) {
				count++;
				if ((count & 0xFFF) == 0) heapPeak = Math.max(heapPeak, usedHeap());
			}
			heapPeak = Math.max(heapPeak, usedHeap());
			rs.close();
		}

		long elapsed = System.nanoTime() - start;
		long heapDelta = heapPeak - heapBefore;
		System.out.printf("  Rows: %,d  |  Elapsed: %,.0f ms  |  Peak heap: +%.1f MB%n",
				count, elapsed / 1e6, heapDelta / (1024.0 * 1024.0));
		return new BenchResult("MariaDB MEMORY scan", ROW_COUNT, elapsed, -1, -1, -1, heapDelta);
	}

	static void printScanComparison(List<BenchResult> results) {
		System.out.println("\n=== Full scan results ===");
		System.out.println("╔══════════════════════════════╦══════════════╦══════════════╦══════════════╗");
		System.out.println("║ Benchmark                    ║  rows/sec    ║  peak heap   ║  heap/row    ║");
		System.out.println("║                              ║              ║    (MB)      ║  (bytes)     ║");
		System.out.println("╠══════════════════════════════╬══════════════╬══════════════╬══════════════╣");
		for (BenchResult r : results) {
			double heapMB   = r.heapDeltaBytes() / (1024.0 * 1024.0);
			double heapPerRow = r.heapDeltaBytes() > 0 ? (double) r.heapDeltaBytes() / r.rows() : 0;
			System.out.printf("║ %-28s ║ %,12.0f ║ %,12.1f ║ %,12.1f ║%n",
					r.label(), r.rowsPerSec(), heapMB, heapPerRow);
		}
		System.out.println("╚══════════════════════════════╩══════════════╩══════════════╩══════════════╝");
	}

	static void printRWComparison(List<BenchResult> results) {
		// Print lookup then update, each split into plain/versioned sub-groups
		for (String kind : new String[]{"lookup", "update"}) {
			System.out.printf("  — %s —%n", kind.toUpperCase());
			System.out.println("╔══════════════════════════════╦══════════════╦══════════════╗");
			System.out.println("║ Benchmark                    ║  avg µs/op   ║   ops/sec    ║");
			System.out.println("╠══════════════════════════════╬══════════════╬══════════════╣");
			for (boolean versionedGroup : new boolean[]{false, true}) {
				List<BenchResult> group = results.stream()
					.filter(r -> r.label().endsWith(kind) && isVersioned(r.label()) == versionedGroup)
					.toList();
				if (group.isEmpty()) continue;
				String gLabel = versionedGroup ? "VERSIONED" : "PLAIN";
				System.out.printf("║ ── %-56s ║%n", gLabel);
				for (BenchResult r : group) {
					double usPerOp = r.elapsedNs() / 1000.0 / r.rows();
					System.out.printf("║ %-28s ║ %,12.1f ║ %,12.0f ║%n", r.label(), usPerOp, r.rowsPerSec());
				}
			}
			System.out.println("╚══════════════════════════════╩══════════════╩══════════════╝");
			System.out.println();
		}

		// Ratio: Convex direct lookup vs plain MariaDB lookup
		BenchResult cdl = results.stream().filter(r -> r.label().equals("Convex direct lookup")).findFirst().orElse(null);
		BenchResult cdu = results.stream().filter(r -> r.label().equals("Convex direct update")).findFirst().orElse(null);
		BenchResult mpl = results.stream().filter(r -> r.label().equals("MariaDB plain lookup")).findFirst().orElse(null);
		BenchResult mpu = results.stream().filter(r -> r.label().equals("MariaDB plain update")).findFirst().orElse(null);
		if (cdl != null && mpl != null)
			System.out.printf("  Convex direct vs MariaDB plain — lookup: %.1fx  update: %.1fx%n",
				(double) mpl.elapsedNs() / cdl.elapsedNs(),
				mpu != null && cdu != null ? (double) mpu.elapsedNs() / cdu.elapsedNs() : Double.NaN);
	}

	// ── Replication / Dedup benchmark ─────────────────────────────────────────

	/**
	 * Measures the incremental sync payload for k changes on a full ROW_COUNT dataset.
	 *
	 * <p>For Convex: the payload is the additional bytes written to the Etch store after
	 * k updates — i.e. the new Merkle tree nodes created. Content-addressing means
	 * unchanged sub-trees cost zero bandwidth: O(k·log n) new cells vs O(n) for a full scan.
	 *
	 * <p>For MariaDB: uses the binlog position delta if binary logging is enabled;
	 * falls back to k × avg_row_size estimate.
	 *
	 * <p>For PostgreSQL: uses pg_wal_lsn_diff to measure exact WAL bytes written.
	 */
	static void benchmarkReplication(boolean mariaAvailable, boolean psqlAvailable) throws Exception {
		System.out.println("\n=== Replication / Dedup Benchmark ===");
		System.out.printf("Incremental sync payload for k changes on %,d-row dataset%n", ROW_COUNT);
		System.out.printf("k values: %s%n%n", Arrays.toString(REPLICATION_K));

		List<ReplicationResult> results = new ArrayList<>();

		// ── Convex ────────────────────────────────────────────────────────
		File pristineFile = new File(STORE_DIR, "cmp-repl-pristine.etch");
		System.out.println("  Building Convex base (" + String.format("%,d", ROW_COUNT) + " rows)...");
		buildConvexReplBase(pristineFile);
		long convexBaseDataBytes = etchDataBytes(pristineFile);
		long convexFullBytes = pristineFile.length();
		System.out.printf("  Convex base: %.1f MB written, %.1f MB allocated%n",
			convexBaseDataBytes / (1024.0 * 1024.0), convexFullBytes / (1024.0 * 1024.0));

		for (int k : REPLICATION_K) {
			System.out.printf("  Convex k=%,d ...", k);
			File workFile = new File(STORE_DIR, "cmp-repl-work.etch");
			Files.copy(pristineFile.toPath(), workFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

			EtchStore store = EtchStore.create(workFile);
			NodeServer<?> server = ConvexDB.createNodeServer(store);
			server.launch();
			SQLDatabase db = SQLDatabase.connect(server.getCursor(), DB_NAME);
			SQLSchema schema = db.tables();  // plain — consistent with base

			for (int i = 0; i < k; i++) {
				long id = Math.abs(RND.nextLong()) % ROW_COUNT;
				schema.insert(TABLE_NAME, Vectors.of(CVMLong.create(id),
					"REPL-" + id, "Updated-" + id, CVMLong.create(RND.nextLong())));
			}
			server.persistSnapshot(server.getLocalValue());
			long payloadBytes = etchDataBytes(workFile) - convexBaseDataBytes;
			server.close();
			store.close();
			workFile.delete();

			System.out.printf(" payload=%.1f KB (%.0f B/change)%n", payloadBytes / 1024.0, (double) payloadBytes / k);
			results.add(new ReplicationResult("Convex", k, payloadBytes, convexBaseDataBytes,
				"etchDataBytes delta: new Merkle cells for " + k + " updates"));
		}

		// ── MariaDB ───────────────────────────────────────────────────────
		if (mariaAvailable) {
			System.out.println("  Loading MariaDB base dataset...");
			setupMariaDB();
			long mariaFullBytes = loadMariaDBDataset();
			System.out.printf("  MariaDB base: %.1f MB%n", mariaFullBytes / (1024.0 * 1024.0));
			long avgRowBytes = mariaFullBytes / ROW_COUNT;

			for (int k : REPLICATION_K) {
				// Apply k updates (we measure two scenarios, not actual binlog)
				applyMariaDBUpdates(k);
				// Scenario A: with binlog/changelog — O(k·row) payload estimate
				long withChangelogBytes = k * avgRowBytes;
				// Scenario B: no changelog — replica must do full table scan = O(n·row)
				long noChangelogBytes = mariaFullBytes;
				System.out.printf("  MariaDB k=%,d  changelog est=%.1f KB  full-scan=%.1f MB%n",
					k, withChangelogBytes / 1024.0, noChangelogBytes / (1024.0 * 1024.0));
				results.add(new ReplicationResult("MariaDB+changelog", k, withChangelogBytes, mariaFullBytes,
					"estimate: k × avg_row_size (" + avgRowBytes + " B/row)"));
				results.add(new ReplicationResult("MariaDB no-log", k, noChangelogBytes, mariaFullBytes,
					"full table scan (no binlog)"));
			}
		}

		// ── PostgreSQL ────────────────────────────────────────────────────
		if (psqlAvailable) {
			System.out.println("  Loading PostgreSQL base dataset...");
			setupPostgres();
			long pgFullBytes = loadPostgresDataset();
			System.out.printf("  PostgreSQL base: %.1f MB%n", pgFullBytes / (1024.0 * 1024.0));

			for (int k : REPLICATION_K) {
				System.out.printf("  PostgreSQL k=%,d ...", k);
				long payloadBytes = measurePostgresReplicationPayload(k);
				System.out.printf(" WAL payload=%.1f KB (%.0f B/change)%n",
					payloadBytes / 1024.0, (double) payloadBytes / k);
				results.add(new ReplicationResult("PostgreSQL", k, payloadBytes, pgFullBytes,
					"pg_wal_lsn_diff: WAL bytes for " + k + " updates"));
			}
		}

		printReplicationComparison(results);
	}

	/** Builds a pristine ROW_COUNT-row Convex Etch store for replication baseline. */
	static void buildConvexReplBase(File storeFile) throws Exception {
		EtchStore store = EtchStore.create(storeFile);
		NodeServer<?> server = ConvexDB.createNodeServer(store);
		server.launch();
		SQLDatabase db = SQLDatabase.connect(server.getCursor(), DB_NAME);
		SQLSchema schema = db.tables();  // plain — no history, measures pure Merkle path overhead
		createConvexTable(schema);

		for (int i = 0; i < ROW_COUNT; i++)
			schema.insert(TABLE_NAME, Vectors.of(CVMLong.create(i),
				"LEID-" + i, "Name-" + i, CVMLong.create(RND.nextLong())));

		server.persistSnapshot(server.getLocalValue());
		server.close();
		store.close();
	}

	/** Loads ROW_COUNT rows into MariaDB (batch mode) and returns table bytes. */
	static long loadMariaDBDataset() throws Exception {
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement ins = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON DUPLICATE KEY UPDATE leid=VALUES(leid), nm=VALUES(nm), rnd=VALUES(rnd)")) {
			for (int i = 0; i < ROW_COUNT; i++) {
				ins.setInt(1, i); ins.setString(2, "LEID-" + i); ins.setString(3, "Name-" + i);
				ins.setLong(4, RND.nextLong());
				ins.addBatch();
				if ((i + 1) % BATCH_SIZE == 0) ins.executeBatch();
			}
			ins.executeBatch();
		}
		return mariaDBTableBytes();
	}

	/** Applies k random updates to MariaDB. */
	static void applyMariaDBUpdates(int k) throws Exception {
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		try (Connection conn = DriverManager.getConnection(connUrl);
				PreparedStatement upd = conn.prepareStatement(
					"UPDATE " + TABLE_NAME + " SET leid=?, nm=?, rnd=? WHERE id=?")) {
			for (int i = 0; i < k; i++) {
				long id = Math.abs(RND.nextLong()) % ROW_COUNT;
				upd.setString(1, "REPL-" + id); upd.setString(2, "Upd-" + id);
				upd.setLong(3, RND.nextLong()); upd.setLong(4, id);
				upd.addBatch();
				if ((i + 1) % BATCH_SIZE == 0) upd.executeBatch();
			}
			upd.executeBatch();
		}
	}

	/** Loads ROW_COUNT rows into PostgreSQL (batch mode) and returns table bytes. */
	static long loadPostgresDataset() throws Exception {
		try (Connection conn = DriverManager.getConnection(PSQL_URL, PSQL_USER, PSQL_PASS);
				PreparedStatement ins = conn.prepareStatement(
					"INSERT INTO " + TABLE_NAME + " (id,leid,nm,rnd) VALUES (?,?,?,?) "
					+ "ON CONFLICT (id) DO UPDATE SET leid=EXCLUDED.leid, nm=EXCLUDED.nm, rnd=EXCLUDED.rnd")) {
			for (int i = 0; i < ROW_COUNT; i++) {
				ins.setInt(1, i); ins.setString(2, "LEID-" + i); ins.setString(3, "Name-" + i);
				ins.setLong(4, RND.nextLong());
				ins.addBatch();
				if ((i + 1) % BATCH_SIZE == 0) ins.executeBatch();
			}
			ins.executeBatch();
		}
		return postgresTableBytes();
	}

	/**
	 * Applies k random updates to PostgreSQL and returns WAL bytes written
	 * as measured by pg_wal_lsn_diff.
	 */
	static long measurePostgresReplicationPayload(int k) throws Exception {
		try (Connection conn = DriverManager.getConnection(PSQL_URL, PSQL_USER, PSQL_PASS);
				Statement stmt = conn.createStatement()) {

			// Capture WAL LSN before
			ResultSet rs = stmt.executeQuery("SELECT pg_current_wal_lsn() AS lsn");
			rs.next();
			String beforeLsn = rs.getString("lsn");

			// Apply k random updates
			try (PreparedStatement upd = conn.prepareStatement(
					"UPDATE " + TABLE_NAME + " SET leid=?, nm=?, rnd=? WHERE id=?")) {
				for (int i = 0; i < k; i++) {
					long id = Math.abs(RND.nextLong()) % ROW_COUNT;
					upd.setString(1, "REPL-" + id); upd.setString(2, "Upd-" + id);
					upd.setLong(3, RND.nextLong()); upd.setLong(4, id);
					upd.addBatch();
					if ((i + 1) % BATCH_SIZE == 0) upd.executeBatch();
				}
				upd.executeBatch();
			}

			// Measure WAL delta
			ResultSet rs2 = stmt.executeQuery(
				"SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '" + beforeLsn + "'::pg_lsn) AS wal_bytes");
			rs2.next();
			return rs2.getLong("wal_bytes");
		}
	}

	static void printReplicationComparison(List<ReplicationResult> results) {
		System.out.println();
		System.out.println("╔══════════════════════╦═══════════╦══════════════╦════════════╦══════════╦═══════════╗");
		System.out.println("║ Database / scenario  ║     k     ║  payload KB  ║  B/change  ║ full MB  ║ payload%  ║");
		System.out.println("╠══════════════════════╬═══════════╬══════════════╬════════════╬══════════╬═══════════╣");
		String lastDb = "";
		for (ReplicationResult r : results) {
			if (!r.db().equals(lastDb)) {
				if (!lastDb.isEmpty())
					System.out.println("╠══════════════════════╬═══════════╬══════════════╬════════════╬══════════╬═══════════╣");
				lastDb = r.db();
			}
			System.out.printf("║ %-20s ║ %,9d ║ %,12.1f ║ %,10.0f ║ %8.1f ║ %9.3f ║%n",
				r.db(), r.k(), r.payloadKB(), r.bytesPerChange(),
				r.fullTableMB(), r.payloadPct());
		}
		System.out.println("╚══════════════════════╩═══════════╩══════════════╩════════════╩══════════╩═══════════╝");
		System.out.println();
		System.out.println("  payload% = (sync payload) / (full table scan) × 100");
		System.out.println("  Convex:              O(k·log n) — only new/changed Merkle nodes transferred.");
		System.out.println("                       Content-addressing: unchanged sub-trees cost zero bandwidth.");
		System.out.println("  MariaDB+changelog:   O(k·row) — estimated from avg row size (requires binlog/WAL).");
		System.out.println("  MariaDB no-log:      O(n·row) — full table scan needed without a changelog.");
		System.out.println("  PostgreSQL:          O(k·row) — WAL measured via pg_wal_lsn_diff.");
	}

	// ── Result persistence ────────────────────────────────────────────────────

	static String gitHash() {
		try {
			Process p = Runtime.getRuntime().exec(new String[]{"git", "rev-parse", "--short", "HEAD"});
			return new String(p.getInputStream().readAllBytes()).trim();
		} catch (Exception e) {
			return "unknown";
		}
	}

	/**
	 * Saves all results to bench-results/<timestamp>-<githash>.json under STORE_DIR.
	 * Format: one JSON object per line — header line first, then one result per line.
	 */
	static File saveResults(List<BenchResult> results) throws IOException {
		String hash = gitHash();
		String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"));
		File dir = new File(STORE_DIR, "bench-results");
		dir.mkdirs();
		File file = new File(dir, ts + "-" + hash + ".json");

		StringBuilder sb = new StringBuilder();
		// Header line
		sb.append(String.format("{\"timestamp\":\"%s\",\"gitHash\":\"%s\",\"rows\":%d}%n", ts, hash, ROW_COUNT));
		// One result per line
		for (BenchResult r : results) {
			sb.append(String.format(
				"{\"label\":\"%s\",\"rows\":%d,\"elapsedNs\":%d," +
				"\"writtenBytes\":%d,\"allocatedBytes\":%d,\"compressedBytes\":%d,\"heapDeltaBytes\":%d," +
				"\"dbRowsBefore\":%d,\"dbRowsAfter\":%d}%n",
				r.label(), r.rows(), r.elapsedNs(),
				r.writtenBytes(), r.allocatedBytes(), r.compressedBytes(), r.heapDeltaBytes(),
				r.dbRowsBefore(), r.dbRowsAfter()));
		}
		Files.writeString(file.toPath(), sb.toString());
		return file;
	}

	/** Loads a saved results file into a map keyed by label. */
	static Map<String, BenchResult> loadResults(File file) throws IOException {
		Map<String, BenchResult> map = new LinkedHashMap<>();
		Pattern p = Pattern.compile("\"(\\w+)\":(-?\\d+|\"[^\"]*\")");
		for (String line : Files.readAllLines(file.toPath())) {
			if (!line.contains("\"label\"")) continue; // skip header line
			Matcher m = p.matcher(line);
			Map<String, String> fields = new LinkedHashMap<>();
			while (m.find()) fields.put(m.group(1), m.group(2).replace("\"", ""));
			long dbRowsBefore = fields.containsKey("dbRowsBefore") ? Long.parseLong(fields.get("dbRowsBefore")) : -1;
			long dbRowsAfter  = fields.containsKey("dbRowsAfter")  ? Long.parseLong(fields.get("dbRowsAfter"))  : -1;
			BenchResult r = new BenchResult(
				fields.get("label"),
				Integer.parseInt(fields.get("rows")),
				Long.parseLong(fields.get("elapsedNs")),
				Long.parseLong(fields.get("writtenBytes")),
				Long.parseLong(fields.get("allocatedBytes")),
				Long.parseLong(fields.get("compressedBytes")),
				Long.parseLong(fields.get("heapDeltaBytes")),
				dbRowsBefore, dbRowsAfter);
			map.put(r.label(), r);
		}
		return map;
	}

	/** Loads the header fields (timestamp, gitHash) from a saved file. */
	static String loadHeader(File file) throws IOException {
		String header = Files.readAllLines(file.toPath()).get(0);
		Pattern p = Pattern.compile("\"(timestamp|gitHash)\":\"([^\"]+)\"");
		Matcher m = p.matcher(header);
		Map<String, String> h = new LinkedHashMap<>();
		while (m.find()) h.put(m.group(1), m.group(2));
		return h.getOrDefault("timestamp", "?") + "  git:" + h.getOrDefault("gitHash", "?");
	}

	/**
	 * Compares two saved result files and prints a regression table.
	 * Positive Δ% = current is faster/smaller (improvement).
	 * Negative Δ% = current is slower/larger (regression).
	 */
	static void compareResultFiles(File baseline, File current) throws IOException {
		Map<String, BenchResult> base = loadResults(baseline);
		Map<String, BenchResult> curr = loadResults(current);
		System.out.println("=== Benchmark Comparison ===");
		System.out.println("  Baseline : " + loadHeader(baseline) + "  (" + baseline.getName() + ")");
		System.out.println("  Current  : " + loadHeader(current)  + "  (" + current.getName()  + ")");
		System.out.println();
		System.out.println("╔══════════════════════════════╦══════════════╦══════════════╦════════╦══════════╦══════════╦════════╗");
		System.out.println("║ Benchmark                    ║ base rows/s  ║  cur rows/s  ║  Δ%    ║base B/row║ cur B/row║  Δ%    ║");
		System.out.println("╠══════════════════════════════╬══════════════╬══════════════╬════════╬══════════╬══════════╬════════╣");

		boolean anyRegression = false;
		for (String label : base.keySet()) {
			BenchResult b = base.get(label);
			BenchResult c = curr.get(label);
			if (c == null) {
				System.out.printf("║ %-28s ║ %,12.0f ║ %12s ║ %6s ║ %8s ║ %8s ║ %6s ║%n",
					label, b.rowsPerSec(), "MISSING", "-", "-", "-", "-");
				continue;
			}
			double throughputDelta = (c.rowsPerSec() - b.rowsPerSec()) / b.rowsPerSec() * 100;
			String storageDeltaStr = "-";
			double storageDelta = Double.NaN;
			if (b.writtenBytes() > 0 && c.writtenBytes() > 0) {
				storageDelta = (c.writtenPerRow() - b.writtenPerRow()) / b.writtenPerRow() * 100;
				storageDeltaStr = String.format("%+.1f%%", storageDelta);
			}
			// Flag regressions: throughput down >5% or storage up >5%
			boolean regression = throughputDelta < -5.0 || storageDelta > 5.0;
			if (regression) anyRegression = true;
			System.out.printf("║ %-28s ║ %,12.0f ║ %,12.0f ║ %s ║ %8.0f ║ %8.0f ║ %s ║%n",
				label,
				b.rowsPerSec(), c.rowsPerSec(),
				coloured(throughputDelta, true),
				b.writtenPerRow() < 0 ? Double.NaN : b.writtenPerRow(),
				c.writtenPerRow() < 0 ? Double.NaN : c.writtenPerRow(),
				storageDeltaStr);
		}
		// Show new benchmarks that weren't in baseline
		for (String label : curr.keySet()) {
			if (!base.containsKey(label)) {
				BenchResult c = curr.get(label);
				System.out.printf("║ %-28s ║ %12s ║ %,12.0f ║ %6s ║ %8s ║ %8.0f ║ %6s ║%n",
					label, "NEW", c.rowsPerSec(), "-", "-",
					c.writtenPerRow() < 0 ? Double.NaN : c.writtenPerRow(), "-");
			}
		}
		System.out.println("╚══════════════════════════════╩══════════════╩══════════════╩════════╩══════════╩══════════╩════════╝");
		System.out.println();
		if (anyRegression)
			System.out.println("  *** REGRESSIONS DETECTED (>5% throughput drop or >5% storage increase) ***");
		else
			System.out.println("  No significant regressions detected.");

		// ── Memory usage comparison ──────────────────────────────────────
		boolean anyHeapRow = false;
		for (String label : base.keySet()) {
			BenchResult b = base.get(label);
			BenchResult c = curr.get(label);
			if (c != null && b.heapDeltaBytes() > 0 && c.heapDeltaBytes() > 0) { anyHeapRow = true; break; }
		}
		if (anyHeapRow) {
			System.out.println();
			System.out.println("=== Memory usage (heap delta) ===");
			System.out.println("╔══════════════════════════════╦══════════════╦══════════════╦════════╦══════════╦══════════╦════════╗");
			System.out.println("║ Benchmark                    ║  base MB     ║  cur MB      ║  Δ%    ║base B/row║ cur B/row║  Δ%    ║");
			System.out.println("╠══════════════════════════════╬══════════════╬══════════════╬════════╬══════════╬══════════╬════════╣");
			boolean anyHeapRegression = false;
			for (String label : base.keySet()) {
				BenchResult b = base.get(label);
				BenchResult c = curr.get(label);
				if (c == null || b.heapDeltaBytes() <= 0 || c.heapDeltaBytes() <= 0) continue;
				double heapDelta = (c.heapDeltaBytes() - b.heapDeltaBytes()) / (double) b.heapDeltaBytes() * 100;
				double bPerRow = (double) b.heapDeltaBytes() / b.rows();
				double cPerRow = (double) c.heapDeltaBytes() / c.rows();
				if (heapDelta > 5.0) anyHeapRegression = true;
				String deltaStr = String.format("%+6.1f%%", heapDelta);
				System.out.printf("║ %-28s ║ %,12.1f ║ %,12.1f ║ %s ║ %8.0f ║ %8.0f ║ %s ║%n",
					label,
					b.heapDeltaMB(), c.heapDeltaMB(),
					deltaStr,
					bPerRow, cPerRow,
					deltaStr);
			}
			// New benchmarks not in baseline
			for (String label : curr.keySet()) {
				if (base.containsKey(label)) continue;
				BenchResult c = curr.get(label);
				if (c.heapDeltaBytes() <= 0) continue;
				double cPerRow = (double) c.heapDeltaBytes() / c.rows();
				System.out.printf("║ %-28s ║ %12s ║ %,12.1f ║ %6s ║ %8s ║ %8.0f ║ %6s ║%n",
					label, "NEW", c.heapDeltaMB(), "-", "-", cPerRow, "-");
			}
			System.out.println("╚══════════════════════════════╩══════════════╩══════════════╩════════╩══════════╩══════════╩════════╝");
			if (anyHeapRegression)
				System.out.println("  *** HEAP REGRESSIONS (>5% increase) ***");
		}

		// ── Row-count comparison ─────────────────────────────────────────
		boolean anyRowIssue = false;
		StringBuilder rowNotes = new StringBuilder();
		for (String label : curr.keySet()) {
			BenchResult c = curr.get(label);
			if (c.dbRowsAfter() < 0) continue;
			BenchResult b = base.get(label);
			// Flag if DB had accumulated rows going in (Etch store not cleared)
			if (c.dbRowsBefore() > 0) {
				rowNotes.append(String.format("  [ACCUMULATED] %-30s  before=%,d  after=%,d  (Etch store not cleared)%n",
					label, c.dbRowsBefore(), c.dbRowsAfter()));
				anyRowIssue = true;
			}
			// Flag if row delta doesn't match expected insert count
			long delta = c.dbRowsAfter() - c.dbRowsBefore();
			if (c.dbRowsBefore() >= 0 && delta != c.rows()) {
				rowNotes.append(String.format("  [MISMATCH]    %-30s  expected +%,d rows but got +%,d%n",
					label, c.rows(), delta));
				anyRowIssue = true;
			}
			// Show change in final row count vs baseline
			if (b != null && b.dbRowsAfter() >= 0) {
				long rowChange = c.dbRowsAfter() - b.dbRowsAfter();
				if (rowChange != 0) {
					rowNotes.append(String.format("  [ROW CHANGE]  %-30s  base after=%,d  cur after=%,d  Δ=%+,d%n",
						label, b.dbRowsAfter(), c.dbRowsAfter(), rowChange));
				}
			}
		}
		if (anyRowIssue || rowNotes.length() > 0) {
			System.out.println("  Row count notes:");
			System.out.print(rowNotes);
		}
	}

	/** Formats a delta percentage as a fixed-width string. */
	static String coloured(double delta, boolean higherIsBetter) {
		// No ANSI colours since output may be piped; use +/- symbols instead
		char flag = (higherIsBetter ? delta >= 0 : delta <= 0) ? '+' : '!';
		return String.format("%c%5.1f%%", flag, delta);
	}

	// ── Helpers ────────────────────────────────────────────────────────────────

	/**
	 * Counts live rows in a Convex table, handling block-packed entries correctly.
	 */
	static long countConvexRows(SQLSchema schema) {
		SQLTable table = schema.getLiveTable(TABLE_NAME);
		if (table == null) return 0;
		convex.core.data.Index<ABlob, ACell> rows = table.getRows();
		if (rows == null) return 0;
		long[] count = {0};
		rows.forEach((k, v) -> {
			if (RowBlock.isBlock(v)) {
				int n = RowBlock.count(v);
				for (int i = 0; i < n; i++) {
					if (SQLRow.isLive(RowBlock.getRow(v, i))) count[0]++;
				}
			} else if (SQLRow.isLive((AVector<ACell>)v)) {
				count[0]++;
			}
		});
		return count[0];
	}

	/** Counts rows in the MariaDB benchmark table. Returns -1 on failure. */
	static long countMariaDBRows() {
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		try (Connection conn = DriverManager.getConnection(connUrl);
				Statement stmt = conn.createStatement()) {
			ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + TABLE_NAME);
			if (rs.next()) return rs.getLong(1);
		} catch (Exception e) {
			// table may not exist yet
		}
		return -1;
	}

	/** Counts rows in the PostgreSQL benchmark table. Returns -1 on failure. */
	static long countPostgresRows() {
		try (Connection conn = DriverManager.getConnection(PSQL_URL, PSQL_USER, PSQL_PASS);
				Statement stmt = conn.createStatement()) {
			ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + TABLE_NAME);
			if (rs.next()) return rs.getLong(1);
		} catch (Exception e) {
			// table may not exist yet
		}
		return -1;
	}

	/**
	 * Finds the most recent previous result file and prints a comparison against it.
	 * Called automatically after every run.
	 */
	static void autoCompare(File currentFile) {
		try {
			File dir = currentFile.getParentFile();
			File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
			if (files == null || files.length < 2) {
				System.out.println("\nNo previous results to auto-compare against.");
				return;
			}
			java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
			File baseline = null;
			for (int i = files.length - 1; i >= 0; i--) {
				if (!files[i].getName().equals(currentFile.getName())) {
					baseline = files[i];
					break;
				}
			}
			if (baseline == null) {
				System.out.println("\nNo previous results to auto-compare against.");
				return;
			}
			System.out.println("\n=== Auto-comparison with previous run ===");
			compareResultFiles(baseline, currentFile);
		} catch (Exception e) {
			System.out.println("\nAuto-compare failed: " + e.getMessage());
		}
	}

	static void createConvexTable(SQLSchema schema) {
		schema.createTable(TABLE_NAME,
			new String[]   { "ID",              "LEID",            "NM",              "RND"             },
			new ConvexType[]{ ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.VARCHAR, ConvexType.INTEGER });
	}

	/**
	 * Queries MariaDB information_schema for on-disk table size (data + index).
	 * Runs ANALYZE TABLE first to refresh the statistics.
	 */
	static long mariaDBTableBytes() throws Exception {
		String connUrl = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		try (Connection conn = DriverManager.getConnection(connUrl);
				Statement stmt = conn.createStatement()) {
			stmt.execute("ANALYZE TABLE " + TABLE_NAME);
			ResultSet rs = stmt.executeQuery(
				"SELECT (DATA_LENGTH + INDEX_LENGTH) AS total_bytes "
				+ "FROM information_schema.TABLES "
				+ "WHERE TABLE_SCHEMA = '" + MARIADB_DB + "' AND TABLE_NAME = '" + TABLE_NAME + "'");
			if (rs.next()) return rs.getLong("total_bytes");
		}
		return -1;
	}

	/**
	 * Returns the used data bytes recorded in the Etch file header, excluding
	 * any pre-allocated (reserved) space at the end of the file.
	 * Etch stores the actual data length as a long at byte offset 4.
	 */
	static long etchDataBytes(File file) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			raf.seek(4); // OFFSET_FILE_SIZE = SIZE_HEADER_MAGIC(2) + SIZE_HEADER_VERSION(2)
			return raf.readLong();
		}
	}

	/**
	 * Estimates compressed file size by sampling up to 4 MB from the file,
	 * compressing with GZIP, and extrapolating the ratio to the full file size.
	 * Uses the Etch data length (not the physical file size) to avoid counting reserved space.
	 */
	static long estimateCompressedSize(File file) throws IOException {
		long fileSize = etchDataBytes(file);
		if (fileSize == 0) return 0;

		int sampleSize = (int) Math.min(fileSize, 4 * 1024 * 1024);
		byte[] sample = new byte[sampleSize];
		try (FileInputStream fis = new FileInputStream(file)) {
			int read = 0;
			while (read < sampleSize) {
				int n = fis.read(sample, read, sampleSize - read);
				if (n < 0) break;
				read += n;
			}
		}

		ByteArrayOutputStream baos = new ByteArrayOutputStream(sampleSize / 2);
		try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
			gz.write(sample);
		}
		double ratio = (double) baos.size() / sampleSize;
		return (long) (fileSize * ratio);
	}

	static long usedHeap() {
		Runtime rt = Runtime.getRuntime();
		return rt.totalMemory() - rt.freeMemory();
	}

	// ── Comparison table ──────────────────────────────────────────────────────

	/**
	 * Returns true if a benchmark label belongs to the "versioned" group.
	 * Versioned = includes history tracking (MariaDB WITH SYSTEM VERSIONING, Convex VersionedSQLSchema).
	 */
	static boolean isVersioned(String label) {
		return label.contains("versioned") || label.contains("Versioned");
	}

	static void printComparison(List<BenchResult> results) {
		System.out.println();
		// Storage columns:
		//   written MB  = actual data bytes (etchDataBytes for Etch; information_schema for MariaDB)
		//   alloc MB    = OS file size for Etch (includes pre-allocated pages); same as written for MariaDB
		//   ~gz/row     = estimated gzip of written bytes / row count
		String header = "╔══════════════════════════════╦══════════════╦═══════════╦══════════╦══════════╦══════════╦══════════╦══════════════╗";
		String cols   = "║ Benchmark                    ║  rows/sec    ║written MB ║ alloc MB ║B/row(wr) ║ ~gz/row  ║ heapΔ MB ║  DB rows     ║";
		String sep    = "╠══════════════════════════════╬══════════════╬═══════════╬══════════╬══════════╬══════════╬══════════╬══════════════╣";
		String foot   = "╚══════════════════════════════╩══════════════╩═══════════╩══════════╩══════════╩══════════╩══════════╩══════════════╝";

		// Print plain group first, then versioned group
		boolean anyAccumulated = false;
		for (boolean versionedGroup : new boolean[]{false, true}) {
			List<BenchResult> group = results.stream()
				.filter(r -> isVersioned(r.label()) == versionedGroup)
				.toList();
			if (group.isEmpty()) continue;

			System.out.println(header);
			System.out.println(cols);
			System.out.println(sep);
			String groupLabel = versionedGroup ? "VERSIONED (with history)" : "PLAIN (no history)";
			System.out.printf("║ %-72s ║%n", " ── " + groupLabel + " ──");
			System.out.println(sep);
			for (BenchResult r : group) {
				String dbRows;
				if (r.dbRowsBefore() >= 0 && r.dbRowsAfter() >= 0) {
					boolean accumulated = r.dbRowsBefore() > 0;
					if (accumulated) anyAccumulated = true;
					dbRows = String.format("%,d→%,d%s", r.dbRowsBefore(), r.dbRowsAfter(), accumulated ? "*" : " ");
				} else {
					dbRows = "-";
				}
				System.out.printf("║ %-28s ║ %,12.0f ║ %9.1f ║ %8.1f ║ %8.0f ║ %8.0f ║ %+8.1f ║ %-12s ║%n",
					r.label(),
					r.rowsPerSec(),
					r.writtenMB()     < 0 ? Double.NaN : r.writtenMB(),
					r.allocatedMB()   < 0 ? Double.NaN : r.allocatedMB(),
					r.writtenPerRow() < 0 ? Double.NaN : r.writtenPerRow(),
					r.compPerRow()    < 0 ? Double.NaN : r.compPerRow(),
					r.heapDeltaMB(),
					dbRows);
			}
			System.out.println(foot);
			System.out.println();
		}
		if (anyAccumulated)
			System.out.println("  * DB had pre-existing rows (Etch store not cleared before run — storage metrics include prior data).");

		// Ratio summary: each Convex variant vs best plain MariaDB result
		BenchResult mariaBest = results.stream()
			.filter(r -> r.label().startsWith("MariaDB") && !isVersioned(r.label()))
			.reduce((a, b) -> a.rowsPerSec() > b.rowsPerSec() ? a : b).orElse(null);
		List<BenchResult> convexResults = results.stream().filter(r -> r.label().startsWith("Convex")).toList();
		if (mariaBest != null && !convexResults.isEmpty()) {
			System.out.println("  Ratios vs best plain MariaDB:");
			for (BenchResult c : convexResults) {
				System.out.printf("    %-30s  throughput: %+.2fx", c.label(), c.rowsPerSec() / mariaBest.rowsPerSec());
				if (c.writtenBytes() > 0 && mariaBest.writtenBytes() > 0) {
					System.out.printf("  storage(written): %.1fx  storage(~gz): %.1fx",
							(double) c.writtenBytes() / mariaBest.writtenBytes(),
							(double) c.compressedBytes() / mariaBest.writtenBytes());
				}
				System.out.println();
			}
		}
	}

	static void printSingleRowComparison(List<BenchResult> results) {
		System.out.println("╔══════════════════════════════╦══════════════╦══════════════╗");
		System.out.println("║ Benchmark                    ║  avg µs/row  ║  rows/sec    ║");
		System.out.println("╠══════════════════════════════╬══════════════╬══════════════╣");
		for (BenchResult r : results) {
			double usPerRow = r.elapsedNs() / 1000.0 / r.rows();
			System.out.printf("║ %-28s ║ %,12.1f ║ %,12.0f ║%n", r.label(), usPerRow, r.rowsPerSec());
		}
		System.out.println("╚══════════════════════════════╩══════════════╩══════════════╝");

		BenchResult convexDirect = results.stream().filter(r -> r.label().contains("direct")).findFirst().orElse(null);
		BenchResult mariaJdbc    = results.stream().filter(r -> r.label().contains("MariaDB JDBC")).findFirst().orElse(null);
		if (convexDirect != null && mariaJdbc != null) {
			System.out.printf("%n  Convex direct vs MariaDB JDBC: %.1fx faster per row%n",
					(double) mariaJdbc.elapsedNs() / convexDirect.elapsedNs());
		}
	}
}
