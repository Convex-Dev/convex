package convex.db;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

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
 * CRDT correctness tests for secondary column indices.
 *
 * <p>Verifies that indices remain consistent after lattice merge — the core
 * property that makes secondary indices safe in a distributed setting.
 *
 * <p>Test pattern: fork two independent replicas, apply mutations on each,
 * merge them, then assert that the index result equals the golden-source
 * (selectAll filtered in-memory) on the merged state.
 *
 * <p>No Thread.sleep() calls are needed: the JVM-global write-sequence counter
 * in SQLSchema guarantees every write gets a unique version number, so LWW ties
 * are impossible within a single process.
 */
public class SecondaryIndexMergeTest {

	private static final String TBL = "employees";
	private static final String[] COLS = {"id", "status", "dept", "score"};
	private static final ConvexType[] TYPES = {
		ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.VARCHAR, ConvexType.INTEGER
	};

	// ── Helpers ──────────────────────────────────────────────────────────────

	private SQLSchema freshSchema() {
		SQLSchema s = SQLDatabase.create("test", AKeyPair.generate()).tables();
		s.createTable(TBL, COLS, TYPES);
		return s;
	}

	private void insert(SQLSchema s, long id, String status, String dept, long score) {
		s.insert(TBL, Vectors.of(CVMLong.create(id),
			Strings.create(status), Strings.create(dept), CVMLong.create(score)));
	}

	/**
	 * Golden-source filter on the given schema: rows where column colIdx == value.
	 */
	private Index<ABlob, AVector<ACell>> golden(SQLSchema s, int colIdx, ACell value) {
		Index<ABlob, AVector<ACell>> all = s.selectAll(TBL);
		Index<ABlob, AVector<ACell>> result = Index.none();
		for (var e : all.entrySet()) {
			AVector<ACell> row = e.getValue();
			ACell cell = (colIdx < row.count()) ? row.get(colIdx) : null;
			if (value.equals(cell)) result = result.assoc(e.getKey(), row);
		}
		return result;
	}

	// ── Concurrent insert on disjoint keys ───────────────────────────────────

	@Test
	void mergeUnion_disjointInserts() {
		SQLSchema base = freshSchema();
		base.createIndex(TBL, "status");

		SQLSchema nodeA = base.fork();
		SQLSchema nodeB = base.fork();

		insert(nodeA, 1, "active", "eng", 80);
		insert(nodeB, 2, "active", "ops", 75);

		nodeA.sync();
		nodeB.sync();

		ACell active = Strings.create("active");
		Index<ABlob, AVector<ACell>> indexed = base.selectByColumn(TBL, "status", active);
		Index<ABlob, AVector<ACell>> golden  = golden(base, 1, active);

		assertEquals(2, indexed.count(), "Merged index must contain both inserted rows");
		assertEquals(golden, indexed, "Index must match golden after merge");
	}

	// ── Concurrent insert on same key (LWW) ──────────────────────────────────

	@Test
	void mergeLWW_sameKey_indexConsistentAfterMerge() {
		// Global write-sequence counter ensures nodeB's write gets a strictly higher
		// version than nodeA's — no sleep needed.
		SQLSchema base = freshSchema();
		base.createIndex(TBL, "status");

		SQLSchema nodeA = base.fork();
		insert(nodeA, 10, "active",   "eng", 90);
		SQLSchema nodeB = base.fork();
		insert(nodeB, 10, "inactive", "eng", 90); // Same PK, strictly higher counter → wins

		nodeA.sync();
		nodeB.sync();

		ACell active   = Strings.create("active");
		ACell inactive = Strings.create("inactive");

		// nodeB's write must win (later counter)
		AVector<ACell> row = base.selectByKey(TBL, CVMLong.create(10));
		assertNotNull(row);
		assertEquals(Strings.create("inactive"), row.get(1),
			"nodeB's 'inactive' must win LWW (later counter)");

		// Index must be consistent with golden source
		assertEquals(golden(base, 1, active),   base.selectByColumn(TBL, "status", active));
		assertEquals(golden(base, 1, inactive),  base.selectByColumn(TBL, "status", inactive));

		// Exactly one row for pk=10
		long total = base.selectByColumn(TBL, "status", active).count()
			+ base.selectByColumn(TBL, "status", inactive).count();
		assertEquals(1, total, "Exactly one value must survive LWW merge");
	}

	// ── Tombstone propagation ─────────────────────────────────────────────────

	@Test
	void tombstone_propagatesAcrossMerge() {
		SQLSchema base = freshSchema();
		base.createIndex(TBL, "status");

		insert(base, 20, "active", "eng", 70);
		insert(base, 21, "active", "ops", 80);

		SQLSchema nodeA = base.fork();
		SQLSchema nodeB = base.fork();

		nodeA.deleteByKey(TBL, CVMLong.create(20));
		insert(nodeB, 22, "active", "hr", 85);

		nodeA.sync();
		nodeB.sync();

		assertNull(base.selectByKey(TBL, CVMLong.create(20)), "Row 20 must be deleted");
		assertNotNull(base.selectByKey(TBL, CVMLong.create(21)));
		assertNotNull(base.selectByKey(TBL, CVMLong.create(22)));

		ACell active = Strings.create("active");
		assertEquals(2, base.selectByColumn(TBL, "status", active).count(),
			"Index must exclude deleted row 20");
		assertEquals(golden(base, 1, active), base.selectByColumn(TBL, "status", active),
			"Index must match golden after tombstone propagation");
	}

	// ── Tombstone wins on equal version (via mergeReplicas) ──────────────────

	@Test
	void tombstone_winsOnEqualTimestamp() {
		AKeyPair kpA = AKeyPair.generate();
		AKeyPair kpB = AKeyPair.generate();
		SQLDatabase dbA = SQLDatabase.create("shared", kpA);
		SQLDatabase dbB = SQLDatabase.create("shared", kpB);

		dbA.tables().createTable(TBL, COLS, TYPES);
		dbB.tables().createTable(TBL, COLS, TYPES);
		dbA.tables().createIndex(TBL, "status");
		dbB.tables().createIndex(TBL, "status");

		insert(dbA.tables(), 30, "active", "eng", 60);

		// Propagate row 30 to dbB
		dbB.mergeReplicas(dbA.exportReplica());
		assertNotNull(dbB.tables().selectByKey(TBL, CVMLong.create(30)));

		// dbB deletes row 30
		dbB.tables().deleteByKey(TBL, CVMLong.create(30));

		// Merge dbB back into dbA — tombstone must propagate
		dbA.mergeReplicas(dbB.exportReplica());

		assertNull(dbA.tables().selectByKey(TBL, CVMLong.create(30)));

		ACell active = Strings.create("active");
		assertEquals(golden(dbA.tables(), 1, active),
			dbA.tables().selectByColumn(TBL, "status", active),
			"Index must reflect tombstone after merge");
	}

	// ── Merge idempotency ─────────────────────────────────────────────────────

	@Test
	void mergeIdempotent() {
		SQLSchema base = freshSchema();
		base.createIndex(TBL, "dept");

		insert(base, 40, "active", "eng", 70);
		insert(base, 41, "active", "ops", 80);

		SQLSchema fork = base.fork();
		insert(fork, 42, "inactive", "eng", 65);
		fork.sync();
		fork.sync(); // double-sync must be a no-op

		ACell eng = Strings.create("eng");
		assertEquals(golden(base, 2, eng), base.selectByColumn(TBL, "dept", eng),
			"Double-merge must be idempotent");
	}

	// ── Index consistency on multiple columns ─────────────────────────────────

	@Test
	void multipleIndices_independentlyConsistent() {
		SQLSchema base = freshSchema();
		base.createIndex(TBL, "status");
		base.createIndex(TBL, "dept");

		SQLSchema nodeA = base.fork();
		SQLSchema nodeB = base.fork();

		insert(nodeA, 50, "active",   "eng", 90);
		insert(nodeA, 51, "inactive", "ops", 60);
		insert(nodeB, 52, "active",   "eng", 75);
		insert(nodeB, 53, "active",   "hr",  85);

		nodeA.sync();
		nodeB.sync();

		ACell active = Strings.create("active");
		ACell eng    = Strings.create("eng");

		assertEquals(golden(base, 1, active), base.selectByColumn(TBL, "status", active),
			"status index must be consistent after merge");
		assertEquals(golden(base, 2, eng),    base.selectByColumn(TBL, "dept",   eng),
			"dept index must be consistent after merge");
	}

	// ── Fuzz: random inserts and deletes ─────────────────────────────────────

	@Test
	void fuzz_randomInsertsAndDeletes_indexConsistent() {
		SQLSchema base = freshSchema();
		base.createIndex(TBL, "status");

		java.util.Random rnd = new java.util.Random(42);
		String[] statuses = {"active", "inactive", "pending"};

		SQLSchema nodeA = base.fork();
		SQLSchema nodeB = base.fork();

		for (int i = 0; i < 30; i++) {
			insert(nodeA, 1000 + i, statuses[rnd.nextInt(statuses.length)], "eng", 50 + rnd.nextInt(50));
		}
		for (int i = 0; i < 10; i++) {
			nodeA.deleteByKey(TBL, CVMLong.create(1000 + rnd.nextInt(30)));
		}
		for (int i = 0; i < 30; i++) {
			insert(nodeB, 2000 + i, statuses[rnd.nextInt(statuses.length)], "ops", 50 + rnd.nextInt(50));
		}
		for (int i = 0; i < 10; i++) {
			nodeB.deleteByKey(TBL, CVMLong.create(2000 + rnd.nextInt(30)));
		}

		nodeA.sync();
		nodeB.sync();

		for (String st : statuses) {
			ACell val = Strings.create(st);
			assertEquals(golden(base, 1, val), base.selectByColumn(TBL, "status", val),
				"Fuzz: index inconsistent for status=" + st);
		}
	}
}
