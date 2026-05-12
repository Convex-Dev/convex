package convex.db.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Random;

import convex.core.data.AVector;
import convex.core.data.ACell;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.ConvexDB;
import convex.db.calcite.ConvexType;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLSchema;
import convex.etch.EtchStore;
import convex.node.NodeServer;

/**
 * Benchmark: PK lookup latency on Etch-backed tables after persist.
 *
 * <p>After persistSnapshot(), Index nodes become RefSoft pointers into
 * the Etch store. This benchmark measures the lookup cost when the JVM
 * must reload tree nodes from Etch (simulating the reporter's 141ms
 * scenario from issue #541).
 */
public class EtchPKLookupBench {

	static final int[] SIZES = {10_000, 100_000};
	static final int LOOKUPS = 1_000;

	public static void main(String[] args) throws Exception {
		System.out.println("=== Etch PK Lookup Benchmark (issue #541) ===\n");

		for (int n : SIZES) {
			System.out.printf("--- %,d rows ---%n", n);
			runBenchmark(n);
			System.out.println();
		}
	}

	static void runBenchmark(int rowCount) throws Exception {
		EtchStore store = EtchStore.createTemp("pk-bench");
		NodeServer<?> server = ConvexDB.createNodeServer(store);
		server.launch();
		ConvexDB cdb = ConvexDB.connect(server.getCursor());
		SQLDatabase db = cdb.database("bench");
		SQLSchema tables = db.tables();

		tables.createTable("t",
				new String[]{"ID", "LEID", "NM"},
				new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.VARCHAR});

		// Insert rows via lattice API (faster than JDBC for bulk insert)
		long t0 = System.nanoTime();
		for (int i = 0; i < rowCount; i++) {
			tables.insert("t", Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i));
		}
		report("Insert", System.nanoTime() - t0);

		// Persist — forces Index nodes into RefSoft (Etch-backed)
		t0 = System.nanoTime();
		server.persistSnapshot(server.getLocalValue());
		report("Persist", System.nanoTime() - t0);

		// Warmup: a few lookups to prime the Etch memory map
		for (int i = 0; i < 10; i++) {
			tables.selectByKey("t", CVMLong.create(i));
		}

		// Random PK lookups
		Random rng = new Random(42);
		long[] timings = new long[LOOKUPS];
		long maxNs = 0;
		int maxIter = 0;
		for (int i = 0; i < LOOKUPS; i++) {
			int key = rng.nextInt(rowCount);
			long start = System.nanoTime();
			AVector<ACell> row = tables.selectByKey("t", CVMLong.create(key));
			long elapsed = System.nanoTime() - start;
			if (row == null) throw new AssertionError("Missing row for key=" + key);
			timings[i] = elapsed;
			if (elapsed > maxNs) { maxNs = elapsed; maxIter = i; }
		}
		reportLookups("PK lookups", timings, maxIter);

		// Row count (should be O(1) with liveCount)
		t0 = System.nanoTime();
		long count = tables.getRowCount("t");
		report("getRowCount() = " + count, System.nanoTime() - t0);

		// SQL queries via JDBC
		cdb.register("bench");
		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=bench")) {

			// Warmup SQL
			try (Statement ws = conn.createStatement()) {
				ws.executeQuery("SELECT * FROM t WHERE ID = 0").close();
			}

			// Random PK lookups: SELECT * FROM t WHERE ID = ?
			rng = new Random(42);
			timings = new long[LOOKUPS];
			maxNs = 0;
			maxIter = 0;
			try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM t WHERE ID = ?")) {
				for (int i = 0; i < LOOKUPS; i++) {
					long key = rng.nextInt(rowCount);
					ps.setLong(1, key);
					long start = System.nanoTime();
					try (ResultSet rs = ps.executeQuery()) {
						if (!rs.next()) throw new AssertionError("Missing row for key=" + key);
					}
					long elapsed = System.nanoTime() - start;
					timings[i] = elapsed;
					if (elapsed > maxNs) { maxNs = elapsed; maxIter = i; }
				}
			}
			reportLookups("SQL WHERE ID=x", timings, maxIter);

			// Top-10 by ID descending: SELECT * FROM t ORDER BY ID DESC LIMIT 10
			timings = new long[LOOKUPS];
			maxNs = 0;
			maxIter = 0;
			try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM t ORDER BY ID DESC LIMIT 10")) {
				for (int i = 0; i < LOOKUPS; i++) {
					long start = System.nanoTime();
					try (ResultSet rs = ps.executeQuery()) {
						int rows = 0;
						while (rs.next()) rows++;
						if (rows != 10) throw new AssertionError("Expected 10 rows, got " + rows);
					}
					long elapsed = System.nanoTime() - start;
					timings[i] = elapsed;
					if (elapsed > maxNs) { maxNs = elapsed; maxIter = i; }
				}
			}
			reportLookups("SQL ORDER BY ID DESC LIMIT 10", timings, maxIter);
		}
		cdb.unregister("bench");

		server.close();
		store.close();
	}

	static void report(String label, long elapsedNs) {
		double ms = elapsedNs / 1_000_000.0;
		System.out.printf("  %-25s %,.1f ms%n", label, ms);
	}

	static void reportLookups(String label, long[] timings, int maxIter) {
		long totalNs = 0;
		long maxNs = 0;
		for (long t : timings) { totalNs += t; if (t > maxNs) maxNs = t; }
		long[] sorted = timings.clone();
		Arrays.sort(sorted);
		long medianNs = sorted[sorted.length / 2];
		double avgUs    = (totalNs / (double) timings.length) / 1_000.0;
		double medianUs = medianNs / 1_000.0;
		double maxUs    = maxNs    / 1_000.0;
		System.out.printf("  %-32s avg %,.1f us, median %,.1f us, max %,.1f us (iter %d)%n",
				label + " (" + timings.length + ")", avgUs, medianUs, maxUs, maxIter);
	}
}
