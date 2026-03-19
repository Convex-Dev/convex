package convex.db.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.ConvexDB;
import convex.db.calcite.ConvexType;
import convex.db.lattice.SQLDatabase;

/**
 * Benchmark: primary key operations (insert, select, update) via lattice API vs JDBC.
 *
 * Tests point operations on tables of varying sizes to expose O(1) vs O(n) behaviour.
 */
public class KeyLookupBench {

	static final int[] TABLE_SIZES = {100, 1_000, 10_000};
	static final int OPS = 1_000; // operations per benchmark run
	static final String DB_NAME = "keybench";

	public static void main(String[] args) throws Exception {
		System.out.println("=== Primary Key Operation Benchmark ===");
		System.out.println("Operations per run: " + OPS);
		System.out.println();

		for (int size : TABLE_SIZES) {
			System.out.println("--- Table size: " + size + " rows ---");
			benchLattice(size);
			benchJdbc(size);
			System.out.println();
		}
		System.exit(0);
	}

	static void benchLattice(int tableSize) {
		SQLDatabase db = SQLDatabase.create(DB_NAME, convex.core.crypto.AKeyPair.generate());
		db.tables().createTable("t", new String[]{"id", "name", "val"},
				new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.INTEGER});

		// Populate
		for (int i = 0; i < tableSize; i++) {
			db.tables().insert("t", Vectors.of(CVMLong.create(i),
					convex.core.data.Strings.create("item-" + i), CVMLong.create(i * 10)));
		}

		// SELECT by key
		long start = System.nanoTime();
		for (int i = 0; i < OPS; i++) {
			db.tables().selectByKey("t", CVMLong.create(i % tableSize));
		}
		report("  Lattice selectByKey", System.nanoTime() - start, OPS);

		// UPDATE (insert with same key)
		start = System.nanoTime();
		for (int i = 0; i < OPS; i++) {
			int key = i % tableSize;
			db.tables().insert("t", Vectors.of(CVMLong.create(key),
					convex.core.data.Strings.create("updated-" + key), CVMLong.create(key * 20)));
		}
		report("  Lattice update      ", System.nanoTime() - start, OPS);

		// INSERT (new keys)
		start = System.nanoTime();
		for (int i = 0; i < OPS; i++) {
			db.tables().insert("t", Vectors.of(CVMLong.create(tableSize + i),
					convex.core.data.Strings.create("new-" + i), CVMLong.create(i)));
		}
		report("  Lattice insert      ", System.nanoTime() - start, OPS);
	}

	static void benchJdbc(int tableSize) throws Exception {
		ConvexDB cdb = ConvexDB.create();
		SQLDatabase db = cdb.database(DB_NAME);
		db.tables().createTable("t", new String[]{"id", "name", "val"},
				new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.INTEGER});
		cdb.register(DB_NAME);

		// Populate via lattice (fast)
		for (int i = 0; i < tableSize; i++) {
			db.tables().insert("t", Vectors.of(CVMLong.create(i),
					convex.core.data.Strings.create("item-" + i), CVMLong.create(i * 10)));
		}

		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=" + DB_NAME)) {

			// SELECT by key via JDBC
			try (PreparedStatement ps = conn.prepareStatement(
					"SELECT id, name, val FROM t WHERE id = ?")) {
				long start = System.nanoTime();
				for (int i = 0; i < OPS; i++) {
					ps.setLong(1, i % tableSize);
					ResultSet rs = ps.executeQuery();
					rs.next();
					rs.close();
				}
				report("  JDBC  selectByKey", System.nanoTime() - start, OPS);
			}

			// UPDATE by key via JDBC
			try (PreparedStatement ps = conn.prepareStatement(
					"UPDATE t SET val = ? WHERE id = ?")) {
				long start = System.nanoTime();
				for (int i = 0; i < OPS; i++) {
					ps.setLong(1, (i % tableSize) * 20);
					ps.setLong(2, i % tableSize);
					ps.executeUpdate();
				}
				report("  JDBC  update      ", System.nanoTime() - start, OPS);
			}

			// INSERT via JDBC
			try (PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO t VALUES (?, ?, ?)")) {
				long start = System.nanoTime();
				for (int i = 0; i < OPS; i++) {
					ps.setLong(1, tableSize + i);
					ps.setString(2, "new-" + i);
					ps.setLong(3, i);
					ps.executeUpdate();
				}
				report("  JDBC  insert      ", System.nanoTime() - start, OPS);
			}
		}
		cdb.unregister(DB_NAME);
	}

	static void report(String label, long elapsedNs, int count) {
		double ms = elapsedNs / 1_000_000.0;
		double rate = count / (ms / 1000.0);
		double usPerOp = (elapsedNs / 1000.0) / count;
		System.out.printf("%-22s %6.0f ms  %8.0f ops/sec  %6.1f us/op%n", label, ms, rate, usPerOp);
	}
}
