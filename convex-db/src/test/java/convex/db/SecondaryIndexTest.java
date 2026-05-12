package convex.db;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
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
 * Lattice-level unit tests for secondary column indices.
 *
 * <p>Tests the index lifecycle (create/drop) and correctness of
 * exact-match and range lookups via the index, always verified against
 * the golden-source: selectAll() filtered in-memory.
 *
 * <p>These tests define the API contract and will be RED until the
 * secondary index feature is implemented.
 */
public class SecondaryIndexTest {

	private SQLSchema tables;

	/** employees(id INTEGER, status VARCHAR, dept VARCHAR, score INTEGER) */
	private static final String TBL = "employees";
	private static final String[] COLS = {"id", "status", "dept", "score"};
	private static final ConvexType[] TYPES = {
		ConvexType.INTEGER, ConvexType.VARCHAR, ConvexType.VARCHAR, ConvexType.INTEGER
	};

	@BeforeEach
	void setUp() {
		tables = SQLDatabase.create("test", AKeyPair.generate()).tables();
		tables.createTable(TBL, COLS, TYPES);
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private void insertEmployee(long id, String status, String dept, long score) {
		tables.insert(TBL, Vectors.of(CVMLong.create(id),
			Strings.create(status), Strings.create(dept), CVMLong.create(score)));
	}

	/**
	 * Golden-source filter: returns all live rows where column {@code colIdx}
	 * equals {@code value}, keyed by primary-key blob.
	 */
	private Index<ABlob, AVector<ACell>> filterByColumn(int colIdx, ACell value) {
		Index<ABlob, AVector<ACell>> all = tables.selectAll(TBL);
		Index<ABlob, AVector<ACell>> result = Index.none();
		for (var e : all.entrySet()) {
			AVector<ACell> row = e.getValue();
			ACell cell = (colIdx < row.count()) ? row.get(colIdx) : null;
			if (value.equals(cell)) {
				result = result.assoc(e.getKey(), row);
			}
		}
		return result;
	}

	// ── Index lifecycle ──────────────────────────────────────────────────────

	@Test
	void createIndex_returnsTrue() {
		assertTrue(tables.createIndex(TBL, "status"),
			"createIndex should succeed on existing table");
	}

	@Test
	void createIndex_idempotent() {
		assertTrue(tables.createIndex(TBL, "status"));
		// Second call on the same column should return false (already exists)
		assertFalse(tables.createIndex(TBL, "status"),
			"createIndex on an already-indexed column should return false");
	}

	@Test
	void createIndex_nonExistentTable_returnsFalse() {
		assertFalse(tables.createIndex("ghost", "status"));
	}

	@Test
	void createIndex_nonExistentColumn_returnsFalse() {
		assertFalse(tables.createIndex(TBL, "no_such_col"));
	}

	@Test
	void hasIndex_falseBeforeCreate() {
		assertFalse(tables.hasIndex(TBL, "status"));
	}

	@Test
	void hasIndex_trueAfterCreate() {
		tables.createIndex(TBL, "status");
		assertTrue(tables.hasIndex(TBL, "status"));
	}

	@Test
	void dropIndex_trueAfterCreate() {
		tables.createIndex(TBL, "status");
		assertTrue(tables.dropIndex(TBL, "status"));
		assertFalse(tables.hasIndex(TBL, "status"));
	}

	@Test
	void dropIndex_falseIfNotPresent() {
		assertFalse(tables.dropIndex(TBL, "status"));
	}

	// ── Index populated on existing data ─────────────────────────────────────

	@Test
	void createIndex_populatesFromExistingRows() {
		insertEmployee(1, "active",   "eng",  90);
		insertEmployee(2, "inactive", "eng",  70);
		insertEmployee(3, "active",   "hr",   80);

		tables.createIndex(TBL, "status");

		ACell active = Strings.create("active");
		Index<ABlob, AVector<ACell>> indexed = tables.selectByColumn(TBL, "status", active);
		Index<ABlob, AVector<ACell>> golden  = filterByColumn(1, active);

		assertEquals(golden.count(), indexed.count(),
			"Index lookup count must match golden filter count");
		assertEquals(golden, indexed,
			"Index lookup must match golden filter result");
	}

	// ── Insert updates index ─────────────────────────────────────────────────

	@Test
	void insertUpdatesIndex() {
		tables.createIndex(TBL, "status");
		insertEmployee(10, "active", "eng", 85);
		insertEmployee(11, "active", "ops", 75);
		insertEmployee(12, "inactive", "eng", 60);

		ACell active = Strings.create("active");
		Index<ABlob, AVector<ACell>> indexed = tables.selectByColumn(TBL, "status", active);
		Index<ABlob, AVector<ACell>> golden  = filterByColumn(1, active);

		assertEquals(golden.count(), indexed.count());
		assertEquals(golden, indexed);
	}

	@Test
	void insertAllUpdatesIndex() {
		tables.createIndex(TBL, "dept");

		List<AVector<ACell>> rows = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			String dept = (i % 3 == 0) ? "eng" : (i % 3 == 1) ? "ops" : "hr";
			rows.add(Vectors.of(CVMLong.create(i), Strings.create("active"),
				Strings.create(dept), CVMLong.create(50 + i)));
		}
		tables.insertAll(TBL, rows);

		ACell eng = Strings.create("eng");
		Index<ABlob, AVector<ACell>> indexed = tables.selectByColumn(TBL, "dept", eng);
		Index<ABlob, AVector<ACell>> golden  = filterByColumn(2, eng);

		assertEquals(golden.count(), indexed.count());
		assertEquals(golden, indexed);
	}

	// ── Delete updates index ─────────────────────────────────────────────────

	@Test
	void deleteUpdatesIndex() {
		tables.createIndex(TBL, "status");
		insertEmployee(20, "active", "eng", 88);
		insertEmployee(21, "active", "ops", 72);

		// Delete row 20
		tables.deleteByKey(TBL, CVMLong.create(20));

		ACell active = Strings.create("active");
		Index<ABlob, AVector<ACell>> indexed = tables.selectByColumn(TBL, "status", active);
		Index<ABlob, AVector<ACell>> golden  = filterByColumn(1, active);

		assertEquals(golden.count(), indexed.count());
		assertEquals(golden, indexed);
	}

	@Test
	void deleteNonExistentDoesNotCorruptIndex() {
		tables.createIndex(TBL, "status");
		insertEmployee(30, "active", "eng", 99);

		// Deleting a non-existent key must not corrupt the index
		tables.deleteByKey(TBL, CVMLong.create(9999));

		ACell active = Strings.create("active");
		assertEquals(filterByColumn(1, active), tables.selectByColumn(TBL, "status", active));
	}

	// ── Exact lookup ─────────────────────────────────────────────────────────

	@Test
	void lookupExact_noMatch() {
		tables.createIndex(TBL, "status");
		insertEmployee(40, "active", "eng", 70);

		Index<ABlob, AVector<ACell>> result =
			tables.selectByColumn(TBL, "status", Strings.create("pending"));
		assertTrue(result.count() == 0, "Lookup for absent value should return empty");
	}

	@Test
	void lookupExact_multipleMatches() {
		tables.createIndex(TBL, "dept");
		insertEmployee(50, "active",   "eng", 80);
		insertEmployee(51, "inactive", "eng", 65);
		insertEmployee(52, "active",   "hr",  90);

		ACell eng = Strings.create("eng");
		Index<ABlob, AVector<ACell>> indexed = tables.selectByColumn(TBL, "dept", eng);
		Index<ABlob, AVector<ACell>> golden  = filterByColumn(2, eng);

		assertEquals(2, indexed.count());
		assertEquals(golden, indexed);
	}

	@Test
	void lookupExact_integerColumn() {
		tables.createIndex(TBL, "score");
		insertEmployee(60, "active", "eng", 100);
		insertEmployee(61, "active", "ops", 100);
		insertEmployee(62, "active", "hr",   90);

		ACell score100 = CVMLong.create(100);
		Index<ABlob, AVector<ACell>> indexed = tables.selectByColumn(TBL, "score", score100);
		Index<ABlob, AVector<ACell>> golden  = filterByColumn(3, score100);

		assertEquals(2, indexed.count());
		assertEquals(golden, indexed);
	}

	// ── Range lookup ─────────────────────────────────────────────────────────

	@Test
	void lookupRange_integer() {
		tables.createIndex(TBL, "score");
		for (int i = 0; i < 10; i++) {
			insertEmployee(100 + i, "active", "eng", 60 + i * 5); // 60,65,70,..105
		}

		// Range [70, 90] inclusive
		Index<ABlob, AVector<ACell>> indexed =
			tables.selectByColumnRange(TBL, "score", CVMLong.create(70), CVMLong.create(90));

		// Golden: filter all rows with 70 ≤ score ≤ 90
		Index<ABlob, AVector<ACell>> all = tables.selectAll(TBL);
		Index<ABlob, AVector<ACell>> golden = Index.none();
		for (var e : all.entrySet()) {
			AVector<ACell> row = e.getValue();
			long s = ((CVMLong) row.get(3)).longValue();
			if (s >= 70 && s <= 90) golden = golden.assoc(e.getKey(), row);
		}

		assertEquals(golden.count(), indexed.count());
		assertEquals(golden, indexed);
	}

	@Test
	void lookupRange_emptyResult() {
		tables.createIndex(TBL, "score");
		insertEmployee(200, "active", "eng", 50);

		// Range [80, 100] — no rows in that range
		Index<ABlob, AVector<ACell>> result =
			tables.selectByColumnRange(TBL, "score", CVMLong.create(80), CVMLong.create(100));
		assertTrue(result.count() == 0);
	}

	// ── No index: fallback behaviour ─────────────────────────────────────────

	@Test
	void lookupWithoutIndex_stillWorksViaScan() {
		// selectByColumn should work even without an explicit index (full scan fallback)
		insertEmployee(300, "active", "eng", 70);
		insertEmployee(301, "inactive", "eng", 80);

		ACell active = Strings.create("active");
		Index<ABlob, AVector<ACell>> result = tables.selectByColumn(TBL, "status", active);
		Index<ABlob, AVector<ACell>> golden = filterByColumn(1, active);

		assertEquals(golden.count(), result.count());
		assertEquals(golden, result);
	}

	// ── selectByColumn on dropped table ──────────────────────────────────────

	@Test
	void lookupOnDroppedTable_returnsEmpty() {
		tables.createIndex(TBL, "status");
		insertEmployee(400, "active", "eng", 70);
		tables.dropTable(TBL);

		Index<ABlob, AVector<ACell>> result =
			tables.selectByColumn(TBL, "status", Strings.create("active"));
		assertEquals(0, result.count());
	}
}
