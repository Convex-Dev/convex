package convex.db.sql;

import java.util.List;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.prim.CVMLong;
import convex.db.lattice.VersionedSQLSchema;
import convex.db.lattice.VersionedSQLTable;

/**
 * Demo: system-versioned table functionality via {@link VersionedSQLSchema}.
 *
 * Shows insert, update, deduplication, deletion, full history, and
 * point-in-time (AS OF) queries — all through the lattice API.
 */
public class TemporalDemo {

	static final String TABLE = "products";

	public static void main(String[] args) {
		System.out.println("=== Convex Temporal Table Demo ===\n");

		VersionedSQLSchema schema = VersionedSQLSchema.create();
		schema.createTable(TABLE, new String[]{"id", "name", "price", "stock"});
		System.out.println("Table created: " + TABLE + "\n");

		// 1. Initial insert
		long t1 = insert(schema, 1L, "Widget Pro", 29.99, 100);
		System.out.println("[t1] Insert  id=1  Widget Pro  $29.99  stock=100");

		sleep(1);

		// 2. Price drop
		long t2 = insert(schema, 1L, "Widget Pro", 24.99, 100);
		System.out.println("[t2] Update  id=1  price → $24.99");

		sleep(1);

		// 3. Duplicate — should be skipped
		boolean written = schema.insert(TABLE, buildRow(1L, "Widget Pro", 24.99, 100));
		System.out.println("[t2b] Duplicate insert id=1: written=" + written + "  (expected false)");

		sleep(1);

		// 4. Stock change
		long t3 = insert(schema, 1L, "Widget Pro", 24.99, 75);
		System.out.println("[t3] Update  id=1  stock → 75");

		sleep(1);

		// 5. Second product
		insert(schema, 2L, "Gadget Plus", 59.99, 50);
		System.out.println("[--] Insert  id=2  Gadget Plus  $59.99  stock=50");

		sleep(1);

		// 6. Delete product 1
		long t4 = delete(schema, 1L);
		System.out.println("[t4] Delete  id=1\n");

		// ── Current state ────────────────────────────────────────────────────
		System.out.println("--- Current state ---");
		printCurrentRow(schema, 1L);
		printCurrentRow(schema, 2L);
		System.out.println();

		// ── Full history for product 1 ────────────────────────────────────────
		System.out.println("--- Full history: id=1 ---");
		printHistory(schema, 1L);
		System.out.println();

		// ── Point-in-time queries ─────────────────────────────────────────────
		System.out.println("--- AS OF t1 (just after initial insert) ---");
		printAsOf(schema, 1L, t1);

		System.out.println("--- AS OF t2 (after price drop) ---");
		printAsOf(schema, 1L, t2);

		System.out.println("--- AS OF t3 (after stock update) ---");
		printAsOf(schema, 1L, t3);

		System.out.println("--- AS OF t4 (after deletion) ---");
		printAsOf(schema, 1L, t4);

		System.out.println("Done.");
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	static long insert(VersionedSQLSchema schema, long id, Object... rest) {
		long ts = System.nanoTime();
		schema.insert(TABLE, buildRow(id, rest));
		return ts;
	}

	static long delete(VersionedSQLSchema schema, long id) {
		long ts = System.nanoTime();
		schema.deleteByKey(TABLE, CVMLong.create(id));
		return ts;
	}

	@SuppressWarnings("unchecked")
	static AVector<ACell> buildRow(long id, Object... rest) {
		Object[] all = new Object[1 + rest.length];
		all[0] = id;
		System.arraycopy(rest, 0, all, 1, rest.length);
		return convex.core.data.Vectors.of(all);
	}

	static void printCurrentRow(VersionedSQLSchema schema, long id) {
		AVector<ACell> row = schema.selectByKey(TABLE, CVMLong.create(id));
		System.out.printf("  id=%-4d  %s%n", id, row == null ? "(deleted / not found)" : row);
	}

	static void printHistory(VersionedSQLSchema schema, long id) {
		List<AVector<ACell>> versions = schema.getHistory(TABLE, CVMLong.create(id));
		if (versions.isEmpty()) { System.out.println("  (no history)"); return; }
		System.out.printf("  %-8s  %-22s  %s%n", "Type", "Nanotime", "Values");
		for (AVector<ACell> entry : versions) {
			long ts   = ((CVMLong) entry.get(1)).longValue();
			long ct   = ((CVMLong) entry.get(2)).longValue();
			AVector<ACell> vals = VersionedSQLTable.getHistoryValues(entry);
			System.out.printf("  %-8s  %-22d  %s%n", changeType(ct), ts, vals == null ? "DELETED" : vals);
		}
	}

	static void printAsOf(VersionedSQLSchema schema, long id, long nanotime) {
		AVector<ACell> entry = schema.getAsOf(TABLE, CVMLong.create(id), nanotime);
		if (entry == null) { System.out.println("  (no record at this time)\n"); return; }
		long ct    = ((CVMLong) entry.get(2)).longValue();
		AVector<ACell> vals = VersionedSQLTable.getHistoryValues(entry);
		System.out.printf("  [%s]  %s%n%n", changeType(ct), vals == null ? "DELETED" : vals);
	}

	static String changeType(long ct) {
		return switch ((int) ct) {
			case (int) VersionedSQLTable.CT_INSERT -> "INSERT";
			case (int) VersionedSQLTable.CT_UPDATE -> "UPDATE";
			case (int) VersionedSQLTable.CT_DELETE -> "DELETE";
			default -> "UNKNOWN";
		};
	}

	static void sleep(long ms) {
		try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
	}
}
