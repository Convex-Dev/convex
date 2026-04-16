package convex.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.lattice.VersionedSQLSchema;
import convex.db.lattice.VersionedSQLTable;

/**
 * Unit tests for {@link VersionedSQLSchema} and {@link VersionedSQLTable}.
 *
 * <p>Covers: insert, update, deduplication, delete, history ordering,
 * point-in-time (AS OF) queries, live row count, and history merge.
 */
public class VersionedSQLSchemaTest {

	private static final String TBL = "items";

	private VersionedSQLSchema schema;

	@BeforeEach
	void setUp() {
		schema = VersionedSQLSchema.create();
		schema.createTable(TBL, new String[]{"id", "name", "value"});
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	AVector<ACell> row(long id, String name, long value) {
		return Vectors.of(CVMLong.create(id), convex.core.data.Strings.create(name), CVMLong.create(value));
	}

	CVMLong pk(long id) { return CVMLong.create(id); }

	// ── Insert ────────────────────────────────────────────────────────────────

	@Test
	void testInsertCreatesLiveRow() {
		schema.insert(TBL, row(1, "alpha", 10));
		AVector<ACell> found = schema.selectByKey(TBL, pk(1));
		assertNotNull(found);
	}

	@Test
	void testInsertRecordsHistoryEntry() {
		schema.insert(TBL, row(1, "alpha", 10));
		List<AVector<ACell>> history = schema.getHistory(TBL, pk(1));
		assertEquals(1, history.size());
		assertEquals(VersionedSQLTable.CT_INSERT, changeType(history.get(0)));
	}

	@Test
	void testInsertRowCount() {
		schema.insert(TBL, row(1, "alpha", 10));
		schema.insert(TBL, row(2, "beta",  20));
		assertEquals(2, schema.getRowCount(TBL));
	}

	// ── Update ────────────────────────────────────────────────────────────────

	@Test
	void testUpdateChangesLiveRow() {
		schema.insert(TBL, row(1, "alpha", 10));
		schema.insert(TBL, row(1, "alpha", 99));

		AVector<ACell> found = schema.selectByKey(TBL, pk(1));
		assertNotNull(found);
		assertEquals(CVMLong.create(99), VersionedSQLTable.getHistoryValues(
				schema.getHistory(TBL, pk(1)).get(1)).get(2));
	}

	@Test
	void testUpdateRecordsUpdateEntry() {
		schema.insert(TBL, row(1, "alpha", 10));
		schema.insert(TBL, row(1, "alpha", 99));

		List<AVector<ACell>> history = schema.getHistory(TBL, pk(1));
		assertEquals(2, history.size());
		assertEquals(VersionedSQLTable.CT_INSERT, changeType(history.get(0)));
		assertEquals(VersionedSQLTable.CT_UPDATE, changeType(history.get(1)));
	}

	@Test
	void testUpdateDoesNotChangeRowCount() {
		schema.insert(TBL, row(1, "alpha", 10));
		schema.insert(TBL, row(1, "alpha", 99));
		assertEquals(1, schema.getRowCount(TBL));
	}

	// ── Deduplication ─────────────────────────────────────────────────────────

	@Test
	void testDuplicateInsertSkipped() {
		schema.insert(TBL, row(1, "alpha", 10));
		boolean written = schema.insert(TBL, row(1, "alpha", 10));
		assertFalse(written, "Identical re-insert should be skipped");
	}

	@Test
	void testDuplicateInsertNoExtraHistory() {
		schema.insert(TBL, row(1, "alpha", 10));
		schema.insert(TBL, row(1, "alpha", 10)); // duplicate
		assertEquals(1, schema.getHistory(TBL, pk(1)).size());
	}

	// ── Delete ────────────────────────────────────────────────────────────────

	@Test
	void testDeleteRemovesLiveRow() {
		schema.insert(TBL, row(1, "alpha", 10));
		schema.deleteByKey(TBL, pk(1));
		assertNull(schema.selectByKey(TBL, pk(1)));
	}

	@Test
	void testDeleteDecrementsRowCount() {
		schema.insert(TBL, row(1, "alpha", 10));
		schema.insert(TBL, row(2, "beta",  20));
		schema.deleteByKey(TBL, pk(1));
		assertEquals(1, schema.getRowCount(TBL));
	}

	@Test
	void testDeleteRecordsDeleteEntry() {
		schema.insert(TBL, row(1, "alpha", 10));
		schema.deleteByKey(TBL, pk(1));

		List<AVector<ACell>> history = schema.getHistory(TBL, pk(1));
		assertEquals(2, history.size());
		assertEquals(VersionedSQLTable.CT_DELETE, changeType(history.get(1)));
	}

	@Test
	void testDeleteNonExistentIsNoop() {
		boolean deleted = schema.deleteByKey(TBL, pk(99));
		assertFalse(deleted);
		assertTrue(schema.getHistory(TBL, pk(99)).isEmpty());
	}

	@Test
	void testDeleteThenInsertAddsNewHistory() {
		schema.insert(TBL, row(1, "alpha", 10));
		schema.deleteByKey(TBL, pk(1));
		schema.insert(TBL, row(1, "alpha", 20)); // re-insert

		List<AVector<ACell>> history = schema.getHistory(TBL, pk(1));
		assertEquals(3, history.size());
		assertEquals(VersionedSQLTable.CT_INSERT, changeType(history.get(0)));
		assertEquals(VersionedSQLTable.CT_DELETE, changeType(history.get(1)));
		assertEquals(VersionedSQLTable.CT_INSERT, changeType(history.get(2)));
		assertNotNull(schema.selectByKey(TBL, pk(1)));
	}

	// ── History ordering ──────────────────────────────────────────────────────

	@Test
	void testHistoryOldestFirst() {
		schema.insert(TBL, row(1, "alpha", 10));
		schema.insert(TBL, row(1, "alpha", 20));
		schema.insert(TBL, row(1, "alpha", 30));

		List<AVector<ACell>> history = schema.getHistory(TBL, pk(1));
		assertEquals(3, history.size());
		// Nanotimes should be monotonically increasing
		long prev = Long.MIN_VALUE;
		for (AVector<ACell> entry : history) {
			long ts = ((CVMLong) entry.get(1)).longValue();
			assertTrue(ts >= prev, "History entries should be ordered oldest-first");
			prev = ts;
		}
	}

	@Test
	void testHistoryIsolatedByPk() {
		schema.insert(TBL, row(1, "alpha", 10));
		schema.insert(TBL, row(1, "alpha", 20));
		schema.insert(TBL, row(2, "beta",  99));

		assertEquals(2, schema.getHistory(TBL, pk(1)).size());
		assertEquals(1, schema.getHistory(TBL, pk(2)).size());
	}

	@Test
	void testHistoryEmptyForUnknownPk() {
		assertTrue(schema.getHistory(TBL, pk(999)).isEmpty());
	}

	// ── AS OF (point-in-time) ─────────────────────────────────────────────────

	@Test
	void testGetAsOfReturnsVersionAtTimestamp() {
		long t1 = System.nanoTime();
		schema.insert(TBL, row(1, "alpha", 10));
		long t2 = System.nanoTime();
		schema.insert(TBL, row(1, "alpha", 20));
		long t3 = System.nanoTime();

		// AS OF before first insert: nothing
		assertNull(schema.getAsOf(TBL, pk(1), t1 - 1));

		// AS OF between first and second insert: first version
		AVector<ACell> v1 = schema.getAsOf(TBL, pk(1), t2 - 1);
		assertNotNull(v1);
		AVector<ACell> vals1 = VersionedSQLTable.getHistoryValues(v1);
		assertEquals(CVMLong.create(10), vals1.get(2));

		// AS OF after second insert: second version
		AVector<ACell> v2 = schema.getAsOf(TBL, pk(1), t3);
		assertNotNull(v2);
		AVector<ACell> vals2 = VersionedSQLTable.getHistoryValues(v2);
		assertEquals(CVMLong.create(20), vals2.get(2));
	}

	@Test
	void testGetAsOfAfterDeleteShowsDeleteEntry() {
		schema.insert(TBL, row(1, "alpha", 10));
		long tDelete = System.nanoTime();
		schema.deleteByKey(TBL, pk(1));
		long tAfter = System.nanoTime();

		AVector<ACell> entry = schema.getAsOf(TBL, pk(1), tAfter);
		assertNotNull(entry);
		assertEquals(VersionedSQLTable.CT_DELETE, changeType(entry));
		assertNull(VersionedSQLTable.getHistoryValues(entry));
	}

	@Test
	void testGetAsOfNullForUnknownPk() {
		assertNull(schema.getAsOf(TBL, pk(999), Long.MAX_VALUE));
	}

	// ── History values ────────────────────────────────────────────────────────

	@Test
	void testGetHistoryValuesMatchesInsertedRow() {
		AVector<ACell> inserted = row(1, "alpha", 42);
		schema.insert(TBL, inserted);

		List<AVector<ACell>> history = schema.getHistory(TBL, pk(1));
		AVector<ACell> vals = VersionedSQLTable.getHistoryValues(history.get(0));
		assertNotNull(vals);
		assertEquals(inserted, vals);
	}

	@Test
	void testGetHistoryValuesNullForDelete() {
		schema.insert(TBL, row(1, "alpha", 10));
		schema.deleteByKey(TBL, pk(1));

		List<AVector<ACell>> history = schema.getHistory(TBL, pk(1));
		AVector<ACell> deleteEntry = history.get(1);
		assertNull(VersionedSQLTable.getHistoryValues(deleteEntry));
	}

	// ── Merge (CRDT union) ────────────────────────────────────────────────────

	@Test
	void testMergeUnionHistory() {
		// Replica A: insert row 1
		VersionedSQLSchema replicaA = VersionedSQLSchema.create();
		replicaA.createTable(TBL, new String[]{"id", "name", "value"});
		replicaA.insert(TBL, row(1, "alpha", 10));

		// Replica B: insert row 2
		VersionedSQLSchema replicaB = VersionedSQLSchema.create();
		replicaB.createTable(TBL, new String[]{"id", "name", "value"});
		replicaB.insert(TBL, row(2, "beta", 20));

		// Merge B into A
		replicaA.cursor().merge(replicaB.cursor().get());

		// Both rows and both histories should be present
		assertNotNull(replicaA.selectByKey(TBL, pk(1)));
		assertNotNull(replicaA.selectByKey(TBL, pk(2)));
		assertEquals(1, replicaA.getHistory(TBL, pk(1)).size());
		assertEquals(1, replicaA.getHistory(TBL, pk(2)).size());
		assertEquals(2, replicaA.getRowCount(TBL));
	}

	// ── Private helpers ───────────────────────────────────────────────────────

	static long changeType(AVector<ACell> histEntry) {
		return ((CVMLong) histEntry.get(2)).longValue();
	}
}
