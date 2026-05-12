package convex.db.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.calcite.ConvexType;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLSchema;

/**
 * Benchmark comparing secondary index lookups in Convex DB vs MariaDB.
 *
 * <p>Measures two things per engine:
 * <ol>
 *   <li><b>Heap delta</b> — bytes allocated to serve the result set.
 *       Index lookup should be proportional to match count, not table size.</li>
 *   <li><b>Speed</b> — repeated lookups timed over {@value #SPEED_REPS} iterations
 *       after a {@value #SPEED_WARMUP}-iteration warmup, reporting ops/sec and
 *       median latency.</li>
 * </ol>
 *
 * <p>Benchmark layout ({@value #TABLE_SIZE} rows, {@value #MATCH_COUNT} matching):
 * <ul>
 *   <li>Convex full scan / index lookup (lattice API)</li>
 *   <li>MariaDB full scan / index lookup (JDBC)</li>
 * </ul>
 *
 * <p>MariaDB connection (overridable via system properties):
 * <pre>
 *   -Dmariadb.host=localhost  (default)
 *   -Dmariadb.port=3306       (default)
 *   -Dmariadb.user=tempuser   (default)
 *   -Dmariadb.pass=123456     (default)
 *   -Dmariadb.db=temp         (default)
 * </pre>
 *
 * <p>Run as a plain main() program (not a JUnit test).
 */
public class SecondaryIndexBench {

	static final int TABLE_SIZE   = 100_000;
	static final int MATCH_COUNT  = 100;     // rows with status="rare"
	static final int SPEED_WARMUP = 50;      // discarded warm-up iterations
	static final int SPEED_REPS   = 200;     // timed iterations

	static final String TBL = "sec_idx_bench";

	// MariaDB connection params
	static final String MARIADB_HOST = System.getProperty("mariadb.host", "localhost");
	static final String MARIADB_PORT = System.getProperty("mariadb.port", "3306");
	static final String MARIADB_USER = System.getProperty("mariadb.user", "tempuser");
	static final String MARIADB_PASS = System.getProperty("mariadb.pass", "123456");
	static final String MARIADB_DB   = System.getProperty("mariadb.db",   "temp");
	static final String MARIADB_URL  =
		"jdbc:mariadb://" + MARIADB_HOST + ":" + MARIADB_PORT + "/" + MARIADB_DB
		+ "?useServerPrepStmts=true";

	public static void main(String[] args) throws Exception {
		System.out.println("=== Secondary Index Heap Bench: Convex DB vs MariaDB ===");
		System.out.printf("Table size: %,d rows, matching rows: %d%n%n", TABLE_SIZE, MATCH_COUNT);

		runConvexBench();

		boolean mariaAvailable = checkMariaDB();
		if (mariaAvailable) {
			runMariaDBBench();
		} else {
			System.out.println("[MariaDB] Skipped — not available.");
			System.out.println("  Start MariaDB and set -Dmariadb.* to enable.");
		}
		System.out.printf("DONE");
	}

	// ── Convex benchmarks ─────────────────────────────────────────────────────

	static void runConvexBench() throws Exception {
		System.out.println("--- Convex DB ---");

		SQLSchema schema = SQLDatabase.create("bench", AKeyPair.generate()).tables();
		schema.createTable(TBL,
			new String[]{"id", "status", "dept", "score"},
			new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR,
				ConvexType.VARCHAR, ConvexType.INTEGER});

		List<AVector<ACell>> rows = new ArrayList<>(TABLE_SIZE);
		for (int i = 0; i < TABLE_SIZE; i++) {
			String status = (i < MATCH_COUNT) ? "rare" : "common";
			rows.add(Vectors.of(CVMLong.create(i), Strings.create(status),
				Strings.create("eng"), CVMLong.create(50 + i % 50)));
		}
		schema.insertAll(TBL, rows);
		rows = null;

		ACell rare = Strings.create("rare");

		// Full scan (no index) — heap
		gc();
		long h0 = usedHeap();
		long scanCount = convexFullScan(schema, 1, rare);
		long scanHeap = usedHeap() - h0;
		System.out.printf("  Full scan   : %,d rows found, heap delta: %+.2f MB%n",
			scanCount, mb(scanHeap));

		// Full scan — speed
		for (int i = 0; i < SPEED_WARMUP; i++) convexFullScan(schema, 1, rare);
		long[] scanTimes = new long[SPEED_REPS];
		for (int i = 0; i < SPEED_REPS; i++) {
			long t = System.nanoTime();
			convexFullScan(schema, 1, rare);
			scanTimes[i] = System.nanoTime() - t;
		}
		printSpeed("  Full scan   ", scanTimes);

		// Index lookup — heap
		schema.createIndex(TBL, "status");

		gc();
		long h2 = usedHeap();
		Index<ABlob, AVector<ACell>> result =
			schema.selectByColumn(TBL, "status", rare);
		long indexHeap = usedHeap() - h2;
		System.out.printf("  Index lookup: %,d rows found, heap delta: %+.2f MB%n",
			result.count(), mb(indexHeap));

		// Index lookup — speed
		for (int i = 0; i < SPEED_WARMUP; i++) schema.selectByColumn(TBL, "status", rare);
		long[] idxTimes = new long[SPEED_REPS];
		for (int i = 0; i < SPEED_REPS; i++) {
			long t = System.nanoTime();
			schema.selectByColumn(TBL, "status", rare);
			idxTimes[i] = System.nanoTime() - t;
		}
		printSpeed("  Index lookup", idxTimes);

		printRatio(scanHeap, indexHeap);
	}

	static long convexFullScan(SQLSchema schema, int colIdx, ACell value) {
		Index<ABlob, AVector<ACell>> all = schema.selectAll(TBL);
		long count = 0;
		for (var e : all.entrySet()) {
			AVector<ACell> row = e.getValue();
			ACell cell = (colIdx < row.count()) ? row.get(colIdx) : null;
			if (value.equals(cell)) count++;
		}
		return count;
	}

	// ── MariaDB benchmarks ────────────────────────────────────────────────────

	static boolean checkMariaDB() {
		try {
			setupMariaDB();
			return true;
		} catch (Exception e) {
			System.out.println("[MariaDB] Not available: " + e.getMessage());
			return false;
		}
	}

	static void setupMariaDB() throws Exception {
		String url = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;
		try (Connection conn = DriverManager.getConnection(url);
				Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("DROP TABLE IF EXISTS " + TBL);
			stmt.executeUpdate(
				"CREATE TABLE " + TBL + " ("
				+ "id INT PRIMARY KEY, "
				+ "status VARCHAR(16), "
				+ "dept VARCHAR(16), "
				+ "score INT"
				+ ") ENGINE=InnoDB");
		}
	}

	static void runMariaDBBench() throws Exception {
		System.out.println("\n--- MariaDB ---");

		String url = MARIADB_URL + "&user=" + MARIADB_USER + "&password=" + MARIADB_PASS;

		// Insert rows
		try (Connection conn = DriverManager.getConnection(url)) {
			conn.setAutoCommit(false);
			try (PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO " + TBL + " VALUES (?, ?, ?, ?)")) {
				for (int i = 0; i < TABLE_SIZE; i++) {
					String status = (i < MATCH_COUNT) ? "rare" : "common";
					ps.setInt(1, i);
					ps.setString(2, status);
					ps.setString(3, "eng");
					ps.setInt(4, 50 + i % 50);
					ps.addBatch();
					if (i % 1000 == 999) ps.executeBatch();
				}
				ps.executeBatch();
			}
			conn.commit();
		}

		String scanSql  = "SELECT id FROM " + TBL + " WHERE status = 'rare'";

		try (Connection conn = DriverManager.getConnection(url)) {
			// Full scan — heap
			gc();
			long h0 = usedHeap();
			long count = mariaCount(conn, scanSql);
			long scanHeap = usedHeap() - h0;
			System.out.printf("  Full scan   : %,d rows found, heap delta: %+.2f MB%n",
				count, mb(scanHeap));

			// Full scan — speed
			for (int i = 0; i < SPEED_WARMUP; i++) mariaCount(conn, scanSql);
			long[] scanTimes = new long[SPEED_REPS];
			for (int i = 0; i < SPEED_REPS; i++) {
				long t = System.nanoTime();
				mariaCount(conn, scanSql);
				scanTimes[i] = System.nanoTime() - t;
			}
			printSpeed("  Full scan   ", scanTimes);

			// Add index
			try (Statement stmt = conn.createStatement()) {
				stmt.executeUpdate("CREATE INDEX idx_status ON " + TBL + " (status)");
			}

			// Index lookup — heap
			gc();
			long h2 = usedHeap();
			long idxCount = mariaCount(conn, scanSql);
			long indexHeap = usedHeap() - h2;
			System.out.printf("  Index lookup: %,d rows found, heap delta: %+.2f MB%n",
				idxCount, mb(indexHeap));

			// Index lookup — speed
			for (int i = 0; i < SPEED_WARMUP; i++) mariaCount(conn, scanSql);
			long[] idxTimes = new long[SPEED_REPS];
			for (int i = 0; i < SPEED_REPS; i++) {
				long t = System.nanoTime();
				mariaCount(conn, scanSql);
				idxTimes[i] = System.nanoTime() - t;
			}
			printSpeed("  Index lookup", idxTimes);

			printRatio(scanHeap, indexHeap);
		}
	}

	static long mariaCount(Connection conn, String sql) throws Exception {
		long count = 0;
		try (Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery(sql)) {
			while (rs.next()) count++;
		}
		return count;
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	static void printSpeed(String label, long[] nanosPerOp) {
		Arrays.sort(nanosPerOp);
		long median = nanosPerOp[nanosPerOp.length / 2];
		long p95    = nanosPerOp[(int) (nanosPerOp.length * 0.95)];
		double opsPerSec = 1_000_000_000.0 / median;
		System.out.printf("%s speed : median %,.0f µs  p95 %,.0f µs  (%,.0f ops/sec)%n",
			label, median / 1_000.0, p95 / 1_000.0, opsPerSec);
	}

	static void printRatio(long scanHeap, long indexHeap) {
		System.out.printf("  Ratio scan/index heap: ");
		if (indexHeap > 0) {
			System.out.printf("%.1fx  (expected ~%.0fx for %d/%d rows)%n",
				(double) scanHeap / indexHeap,
				(double) TABLE_SIZE / MATCH_COUNT,
				TABLE_SIZE, MATCH_COUNT);
		} else {
			System.out.printf("N/A (index heap ≤ 0)%n");
		}
	}

	static void gc() { System.gc(); System.gc(); }

	static long usedHeap() {
		Runtime rt = Runtime.getRuntime();
		return rt.totalMemory() - rt.freeMemory();
	}

	static double mb(long bytes) { return bytes / 1024.0 / 1024.0; }
}
