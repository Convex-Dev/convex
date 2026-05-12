package convex.db.sql;

import java.util.ArrayList;
import java.util.List;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.calcite.ConvexTableEnumerator;
import convex.db.calcite.ConvexType;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLSchema;
import convex.db.lattice.SQLTable;

/**
 * Verifies that the blockVec fast path in ConvexTableEnumerator
 * is taken after a batch insert, and measures heap delta during scan.
 *
 * This is an in-memory test (no Etch) — it proves the code path works
 * and measures blockVec vs Index scan overhead for in-memory data.
 * The Etch benefit (Index trie reload from RefSoft) is larger and is
 * measured by DBComparisonBench.
 */
public class BlockVecHeapBench {

	static final int[] SIZES = {10_000, 100_000};

	public static void main(String[] args) throws Exception {
		System.out.println("=== BlockVec Heap Bench ===\n");

		for (int n : SIZES) {
			System.out.printf("--- %,d rows ---%n", n);

			// --- Individual inserts (blockVec invalidated) ---
			{
				SQLDatabase db = SQLDatabase.create("bench", AKeyPair.generate());
				SQLSchema tables = db.tables();
				tables.createTable("t",
						new String[]{"ID", "NM"},
						new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR});
				for (int i = 0; i < n; i++) {
					tables.insert("t", Vectors.of(CVMLong.create(i), "Name-" + i));
				}
				SQLTable tbl = tables.getLiveTable("t");
				AVector<ACell> bv = tbl.getBlockVec();
				System.out.printf("  Individual inserts: blockVec=%s%n",
						bv == null ? "null (Index path)" : "present (" + bv.count() + " blocks)");
				long h0 = usedHeap();
				int count = scanViaEnumerator(tables, "t");
				long h1 = usedHeap();
				System.out.printf("  Scan (Index path):  %,d rows, heap delta: %+.1f MB%n",
						count, (h1 - h0) / 1024.0 / 1024.0);
			}

			// --- Batch insert (blockVec populated) ---
			{
				SQLDatabase db = SQLDatabase.create("bench", AKeyPair.generate());
				SQLSchema tables = db.tables();
				tables.createTable("t",
						new String[]{"ID", "NM"},
						new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR});

				List<AVector<ACell>> rows = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					rows.add(Vectors.of(CVMLong.create(i), "Name-" + i));
				}
				tables.insertAll("t", rows);
				SQLTable tbl = tables.getLiveTable("t");

				AVector<ACell> bv = tbl.getBlockVec();
				System.out.printf("  Batch insert:       blockVec=%s%n",
						bv == null ? "null" : "present (" + bv.count() + " blocks)");
				long h0 = usedHeap();
				int count = scanViaEnumerator(tables, "t");
				long h1 = usedHeap();
				System.out.printf("  Scan (blockVec path): %,d rows, heap delta: %+.1f MB%n",
						count, (h1 - h0) / 1024.0 / 1024.0);
			}

			System.out.println();
		}
	}

	static int scanViaEnumerator(SQLSchema tables, String tableName) {
		ConvexTableEnumerator e = new ConvexTableEnumerator(tables, tableName);
		int count = 0;
		while (e.moveNext()) count++;
		return count;
	}

	static long usedHeap() {
		Runtime rt = Runtime.getRuntime();
		return rt.totalMemory() - rt.freeMemory();
	}
}
