package convex.db.sql;

import convex.core.crypto.AKeyPair;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.calcite.ConvexType;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLSchema;

/**
 * Benchmark verifying that row count, PK lookup, and full scan
 * scale correctly with table size. Targets issue #541.
 *
 * Expect:
 *   - getRowCount():  O(1)  — constant regardless of table size
 *   - selectByKey():  O(log n) — microseconds even at 100K rows
 *   - selectAll():    O(n)  — linear, single-pass
 */
public class ScalingBench {

	static final int[] SIZES = {1_000, 10_000, 100_000};

	public static void main(String[] args) {
		System.out.println("=== Convex DB Scaling Benchmark (issue #541) ===\n");

		for (int n : SIZES) {
			System.out.printf("--- %,d rows ---%n", n);
			AKeyPair kp = AKeyPair.generate();
			SQLDatabase db = SQLDatabase.create("bench", kp);
			SQLSchema tables = db.tables();
			tables.createTable("t",
					new String[]{"ID", "LEID", "NM"},
					new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.VARCHAR});

			// Insert
			long t0 = System.nanoTime();
			for (int i = 0; i < n; i++) {
				tables.insert("t", Vectors.of(CVMLong.create(i), "LEID-" + i, "Name-" + i));
			}
			report("Insert " + n, System.nanoTime() - t0);

			// Row count (should be O(1))
			t0 = System.nanoTime();
			long count = tables.getRowCount("t");
			report("getRowCount() = " + count, System.nanoTime() - t0);

			// PK lookup (should be O(log n))
			t0 = System.nanoTime();
			var row = tables.selectByKey("t", CVMLong.create(n / 2));
			report("selectByKey(mid)", System.nanoTime() - t0);

			// Full scan (should be O(n))
			t0 = System.nanoTime();
			var all = tables.selectAll("t");
			report("selectAll() -> " + all.count(), System.nanoTime() - t0);

			System.out.println();
		}
	}

	static void report(String label, long elapsedNs) {
		double us = elapsedNs / 1_000.0;
		if (us > 1_000_000) {
			System.out.printf("  %-30s %,.1f ms%n", label, us / 1_000.0);
		} else if (us > 1_000) {
			System.out.printf("  %-30s %,.1f ms%n", label, us / 1_000.0);
		} else {
			System.out.printf("  %-30s %,.1f us%n", label, us);
		}
	}
}
