package convex.db.sql;

import java.io.File;
import java.io.RandomAccessFile;

import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.ConvexDB;
import convex.db.calcite.ConvexType;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLSchema;
import convex.db.lattice.VersionedSQLSchema;
import convex.etch.EtchStore;
import convex.node.NodeServer;

/**
 * Demonstrates Etch GC compaction for both regular and versioned SQL tables.
 *
 * <p>Etch is append-only. Every write touches ~depth Index nodes in the radix
 * tree; each touched node becomes a new cell while the old version remains as
 * a dead cell. Compaction copies only the reachable cells to a fresh file.
 *
 * <p>Two scenarios are compared:
 * <ul>
 *   <li><b>Regular table</b>: updates overwrite rows; dead cells = old path nodes only</li>
 *   <li><b>Versioned table</b>: history entries accumulate (never deleted), so the live
 *       set is larger but dead path-node churn is similar per write</li>
 * </ul>
 * 
 * Compaction is required to remove dead index nodes
 * It is also achieved by creating a fresh replica node
 * 
 */
public class EtchCompactionDemo {

	static final int    ROW_COUNT     = 200_000;
	static final int    UPDATE_PASSES = 5;       // full-table update passes after initial insert
	static final int    PERSIST_EVERY = 1_000;   // force setRootData every N rows to generate dead cells
	static final String DB_NAME       = "bench";
	static final String TABLE_NAME    = "t";
	static final String STORE_DIR     = "/home/rob/etch/";

	public static void main(String[] args) throws Exception {
		runRegular();
		System.out.println();
		runVersioned();
	}

	// ── Regular table ─────────────────────────────────────────────────────

	static void runRegular() throws Exception {
		System.out.println("=== Regular table compaction ===");
		File srcFile = new File(STORE_DIR, "compaction-src.etch");
		File dstFile = new File(STORE_DIR, "compaction-dst.etch");

		// ── Phase 1: build a bloated store ────────────────────────────────
		System.out.printf("Building store: %,d rows → %s%n", ROW_COUNT, srcFile);

		if (srcFile.exists()) srcFile.delete();
		if (dstFile.exists()) dstFile.delete();

		EtchStore srcStore = EtchStore.create(srcFile);
		NodeServer<?> server = ConvexDB.createNodeServer(srcStore);
		server.launch();
		ConvexDB cdb = ConvexDB.connect(server.getCursor());

		SQLDatabase db = cdb.database(DB_NAME);
		SQLSchema schema = db.tables();
		schema.createTable(TABLE_NAME, new String[]{"id", "val1", "val2"},
				new ConvexType[]{ConvexType.INTEGER, ConvexType.INTEGER, ConvexType.INTEGER});

		long insertStart = System.nanoTime();
		for (int i = 0; i < ROW_COUNT; i++) {
			schema.insert(TABLE_NAME, Vectors.of(CVMLong.create(i), CVMLong.create(i * 2L), CVMLong.create(i * 3L)));
			if ((i + 1) % PERSIST_EVERY == 0) server.persistSnapshot(server.getLocalValue());
		}
		long insertMs = (System.nanoTime() - insertStart) / 1_000_000;
		System.out.printf("  Inserted %,d rows in %,d ms  (%.0f rows/s)%n",
				ROW_COUNT, insertMs, ROW_COUNT / (insertMs / 1000.0));

		System.out.printf("  Updating all rows %d times to generate dead cells...%n", UPDATE_PASSES);
		long updateStart = System.nanoTime();
		for (int pass = 1; pass <= UPDATE_PASSES; pass++) {
			for (int i = 0; i < ROW_COUNT; i++) {
				schema.insert(TABLE_NAME, Vectors.of(CVMLong.create(i),
						CVMLong.create(i * 2L + pass), CVMLong.create(i * 3L + pass)));
				if ((i + 1) % PERSIST_EVERY == 0) server.persistSnapshot(server.getLocalValue());
			}
		}
		long updateMs = (System.nanoTime() - updateStart) / 1_000_000;
		System.out.printf("  Updated %,d rows × %d passes in %,d ms%n%n",
				ROW_COUNT, UPDATE_PASSES, updateMs);

		server.persistSnapshot(server.getLocalValue());
		server.close();
		srcStore.close();

		long srcBytes = etchDataBytes(srcFile);
		System.out.printf("  Etch size before compaction: %,.1f MB  (%,d B/row)%n%n",
				srcBytes / 1_048_576.0, srcBytes / ROW_COUNT);

		// ── Phase 2: compact ──────────────────────────────────────────────
		System.out.printf("Compacting → %s%n", dstFile);
		EtchStore srcReopen = EtchStore.create(srcFile);
		long compactStart = System.nanoTime();
		EtchStore dstStore = srcReopen.compact(dstFile);
		long compactMs = (System.nanoTime() - compactStart) / 1_000_000;
		srcReopen.close();

		long dstBytes = etchDataBytes(dstFile);
		double ratio = (double) srcBytes / dstBytes;
		System.out.printf("  Compaction done in %,d ms%n", compactMs);
		System.out.printf("  Etch size after:  %,.1f MB  (%,d B/row)%n", dstBytes / 1_048_576.0, dstBytes / ROW_COUNT);
		System.out.printf("  Compaction ratio: %.1f×%n%n", ratio);

		// ── Phase 3: verify ───────────────────────────────────────────────
		System.out.println("Verifying data integrity in compacted store...");
		NodeServer<?> server2 = ConvexDB.createNodeServer(dstStore);
		server2.launch();
		ConvexDB cdb2 = ConvexDB.connect(server2.getCursor());
		SQLSchema schema2 = cdb2.database(DB_NAME).tables();

		long rowCount = schema2.getRowCount(TABLE_NAME);
		System.out.printf("  Row count: %,d%n", rowCount);

		int errors = 0;
		for (int i : new int[]{0, 1, ROW_COUNT / 2, ROW_COUNT - 1}) {
			if (schema2.selectByKey(TABLE_NAME, CVMLong.create(i)) == null) {
				System.out.printf("  ERROR: row %d missing!%n", i);
				errors++;
			}
		}
		System.out.printf("  Spot checks: %s%n", errors == 0 ? "PASS" : errors + " FAILURES");
		server2.close();
		dstStore.close();

		printSummary(srcBytes, dstBytes, ratio, rowCount);
	}

	// ── Versioned table ───────────────────────────────────────────────────

	static void runVersioned() throws Exception {
		System.out.println("=== Versioned table compaction ===");
		File srcFile = new File(STORE_DIR, "compaction-versioned-src.etch");
		File dstFile = new File(STORE_DIR, "compaction-versioned-dst.etch");

		// ── Phase 1: build a bloated store ────────────────────────────────
		System.out.printf("Building store: %,d rows → %s%n", ROW_COUNT, srcFile);

		if (srcFile.exists()) srcFile.delete();
		if (dstFile.exists()) dstFile.delete();

		EtchStore srcStore = EtchStore.create(srcFile);
		NodeServer<?> server = ConvexDB.createNodeServer(srcStore);
		server.launch();
		ConvexDB cdb = ConvexDB.connect(server.getCursor());

		SQLDatabase db = cdb.database(DB_NAME);
		VersionedSQLSchema schema = VersionedSQLSchema.wrap(db.tables());
		schema.createTable(TABLE_NAME, new String[]{"id", "val1", "val2"},
				new ConvexType[]{ConvexType.INTEGER, ConvexType.INTEGER, ConvexType.INTEGER});

		long insertStart = System.nanoTime();
		for (int i = 0; i < ROW_COUNT; i++) {
			schema.insert(TABLE_NAME, Vectors.of(CVMLong.create(i), CVMLong.create(i * 2L), CVMLong.create(i * 3L)));
			if ((i + 1) % PERSIST_EVERY == 0) server.persistSnapshot(server.getLocalValue());
		}
		long insertMs = (System.nanoTime() - insertStart) / 1_000_000;
		System.out.printf("  Inserted %,d rows in %,d ms  (%.0f rows/s)%n",
				ROW_COUNT, insertMs, ROW_COUNT / (insertMs / 1000.0));

		System.out.printf("  Updating all rows %d times (history entries accumulate)...%n", UPDATE_PASSES);
		long updateStart = System.nanoTime();
		for (int pass = 1; pass <= UPDATE_PASSES; pass++) {
			for (int i = 0; i < ROW_COUNT; i++) {
				schema.insert(TABLE_NAME, Vectors.of(CVMLong.create(i),
						CVMLong.create(i * 2L + pass), CVMLong.create(i * 3L + pass)));
				if ((i + 1) % PERSIST_EVERY == 0) server.persistSnapshot(server.getLocalValue());
			}
		}
		long updateMs = (System.nanoTime() - updateStart) / 1_000_000;
		System.out.printf("  Updated %,d rows × %d passes in %,d ms%n%n",
				ROW_COUNT, UPDATE_PASSES, updateMs);

		server.persistSnapshot(server.getLocalValue());
		server.close();
		srcStore.close();

		long srcBytes = etchDataBytes(srcFile);
		System.out.printf("  Etch size before compaction: %,.1f MB  (%,d B/row)%n%n",
				srcBytes / 1_048_576.0, srcBytes / ROW_COUNT);

		// ── Phase 2: compact ──────────────────────────────────────────────
		System.out.printf("Compacting → %s%n", dstFile);
		EtchStore srcReopen = EtchStore.create(srcFile);
		long compactStart = System.nanoTime();
		EtchStore dstStore = srcReopen.compact(dstFile);
		long compactMs = (System.nanoTime() - compactStart) / 1_000_000;
		srcReopen.close();

		long dstBytes = etchDataBytes(dstFile);
		double ratio = (double) srcBytes / dstBytes;
		System.out.printf("  Compaction done in %,d ms%n", compactMs);
		System.out.printf("  Etch size after:  %,.1f MB  (%,d B/row)%n", dstBytes / 1_048_576.0, dstBytes / ROW_COUNT);
		System.out.printf("  Compaction ratio: %.1f×%n%n", ratio);

		// ── Phase 3: verify ───────────────────────────────────────────────
		System.out.println("Verifying data integrity in compacted store...");
		NodeServer<?> server2 = ConvexDB.createNodeServer(dstStore);
		server2.launch();
		ConvexDB cdb2 = ConvexDB.connect(server2.getCursor());
		VersionedSQLSchema schema2 = VersionedSQLSchema.wrap(cdb2.database(DB_NAME).tables());

		long rowCount = schema2.getRowCount(TABLE_NAME);
		System.out.printf("  Live row count: %,d%n", rowCount);

		int errors = 0;
		for (int i : new int[]{0, 1, ROW_COUNT / 2, ROW_COUNT - 1}) {
			var row = schema2.selectByKey(TABLE_NAME, CVMLong.create(i));
			if (row == null) {
				System.out.printf("  ERROR: row %d missing!%n", i);
				errors++;
			}
			// Verify history: expect 1 insert + UPDATE_PASSES updates = UPDATE_PASSES+1 entries
			var history = schema2.getHistory(TABLE_NAME, CVMLong.create(i));
			if (history.size() != UPDATE_PASSES + 1) {
				System.out.printf("  ERROR: row %d history size %d (expected %d)!%n",
						i, history.size(), UPDATE_PASSES + 1);
				errors++;
			}
		}
		System.out.printf("  Spot checks (rows + history): %s%n", errors == 0 ? "PASS" : errors + " FAILURES");
		server2.close();
		dstStore.close();

		long historyEntries = (long) ROW_COUNT * (UPDATE_PASSES + 1);
		System.out.printf("  Total history entries preserved: %,d%n", historyEntries);
		printSummary(srcBytes, dstBytes, ratio, rowCount);
	}

	// ── Helpers ───────────────────────────────────────────────────────────

	static void printSummary(long srcBytes, long dstBytes, double ratio, long rowCount) {
		System.out.println();
		System.out.println("=== Summary ===");
		System.out.printf("  Before compaction: %,.1f MB%n", srcBytes / 1_048_576.0);
		System.out.printf("  After  compaction: %,.1f MB%n", dstBytes / 1_048_576.0);
		System.out.printf("  Space saved:       %,.1f MB  (%.1f%% of original)%n",
				(srcBytes - dstBytes) / 1_048_576.0, 100.0 * (srcBytes - dstBytes) / srcBytes);
		System.out.printf("  Compaction ratio:  %.1f×%n", ratio);
		System.out.printf("  Live rows:         %,d%n", rowCount);
	}

	/** Reads the Etch data length field from offset 4 (past magic + version bytes). */
	static long etchDataBytes(File file) throws Exception {
		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			raf.seek(4); // OFFSET_FILE_SIZE = SIZE_HEADER_MAGIC(2) + SIZE_HEADER_VERSION(2)
			return raf.readLong();
		}
	}
}
