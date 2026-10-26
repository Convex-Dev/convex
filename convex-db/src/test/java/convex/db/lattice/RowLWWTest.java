package convex.db.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.core.data.Ref;
import convex.core.data.RefSoft;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.store.NullStore;

/**
 * The per-row last-write-wins policy. Rows merge by key whichever schema wins, and a row
 * held on one side only survives if it was written at or after the other side's utime,
 * so rows from before a table was dropped and created again never return.
 */
public class RowLWWTest {

	private static final AVector<AVector<ACell>> SCHEMA = Vectors.of(Vectors.of(Strings.create("id"), null, null, null));
	private static final AVector<AVector<ACell>> OTHER_SCHEMA = Vectors.of(Vectors.of(Strings.create("key"), null, null, null));
	private static final ABlob R = Strings.create("r").toBlob();
	private static final ABlob Q = Strings.create("q").toBlob();

	private static AVector<ACell> row(String value, long time) {
		return SQLRow.create(Vectors.of(Strings.create(value)), CVMLong.create(time));
	}

	/** A table entry: live with a schema, dropped without one. */
	private static AVector<ACell> entry(AVector<AVector<ACell>> schema, long utime, Index<ABlob, AVector<ACell>> rows) {
		Index<ABlob, AVector<ACell>> held = (schema == null && rows.isEmpty()) ? null : rows;
		return Vectors.of(schema, held, CVMLong.create(utime), CVMLong.create(SQLTable.computeLiveCount(held)));
	}

	private static AVector<ACell> rowsOnly(Index<ABlob, AVector<ACell>> rows) {
		return Vectors.of(null, rows, null, CVMLong.create(SQLTable.computeLiveCount(rows)));
	}

	@SuppressWarnings("unchecked")
	private static Index<ABlob, AVector<ACell>> rows(AVector<ACell> entry) {
		return (Index<ABlob, AVector<ACell>>) entry.get(SQLTable.POS_ROWS);
	}

	/** Rows a reader sees: none in a dropped table. */
	private static long visible(AVector<ACell> entry) {
		return SQLTable.isLiveState(entry) ? SQLTable.computeLiveCount(rows(entry)) : 0;
	}

	// ========== One writer ==========

	/** Every copy that has seen the writer's latest state holds it, whatever else it saw and in whatever order. */
	@Test
	public void testOneWriterDropAndRecreateConverges() {
		AVector<ACell> created = entry(SCHEMA, 1, Index.none());
		AVector<ACell> written = entry(SCHEMA, 1, Index.of(R, row("r", 2)));
		AVector<ACell> dropped = SQLTable.createTombstoneState(CVMLong.create(3));
		AVector<ACell> recreated = entry(SCHEMA, 4, Index.none());
		List<AVector<ACell>> history = List.of(created, written, dropped, recreated);

		for (List<AVector<ACell>> seen : subsetsWithLast(history)) {
			for (List<AVector<ACell>> order : permutations(seen)) {
				AVector<ACell> copy = null;
				for (AVector<ACell> state : order) copy = RowLWW.mergeTable(copy, state);
				assertEquals(recreated, copy, "copy that saw " + order.size() + " states");
			}
		}
	}

	@Test
	public void testRowsFromBeforeRecreationDoNotReturn() {
		AVector<ACell> stale = entry(SCHEMA, 1, Index.of(R, row("old", 2)));
		AVector<ACell> recreated = entry(SCHEMA, 4, Index.of(Q, row("new", 5)));
		for (AVector<ACell> merged : List.of(RowLWW.mergeTable(stale, recreated), RowLWW.mergeTable(recreated, stale))) {
			assertEquals(CVMLong.create(4), merged.get(SQLTable.POS_UTIME));
			assertNull(rows(merged).get(R));
			assertEquals(1, visible(merged));
		}
	}

	// ========== Several writers ==========

	@Test
	public void testRowsOfOneCreationMergeByKey() {
		AVector<ACell> a = entry(SCHEMA, 1, Index.of(R, row("r", 2)));
		AVector<ACell> b = entry(SCHEMA, 1, Index.of(Q, row("q", 3)));
		assertEquals(2, visible(RowLWW.mergeTable(a, b)));
		assertEquals(RowLWW.mergeTable(a, b), RowLWW.mergeTable(b, a));
	}

	/** Two creations of a table are different tables: rows written to the earlier one before the later creation are dropped. */
	@Test
	public void testIndependentCreationsAreDifferentTables() {
		AVector<ACell> first = entry(SCHEMA, 1, Index.of(R, row("before", 2)));
		AVector<ACell> second = entry(SCHEMA, 3, Index.of(Q, row("second", 4)));
		AVector<ACell> merged = RowLWW.mergeTable(first, second);
		assertEquals(merged, RowLWW.mergeTable(second, first));
		assertNull(rows(merged).get(R));
		assertEquals(1, visible(merged));

		// A row written to the earlier creation after the later one survives
		AVector<ACell> later = entry(SCHEMA, 1, Index.of(R, row("after", 5)));
		assertEquals(2, visible(RowLWW.mergeTable(later, second)));
	}

	/** A dropped table holds rows written after the drop, unseen until a later creation; rows from before it are dropped. */
	@Test
	public void testDroppedTableHoldsOnlyLaterRows() {
		AVector<ACell> live = entry(SCHEMA, 1, Index.of(R, row("before", 2), Q, row("after", 5)));
		AVector<ACell> dropped = RowLWW.mergeTable(SQLTable.createTombstoneState(CVMLong.create(3)), live);
		assertFalse(SQLTable.isLiveState(dropped));
		assertEquals(0, visible(dropped));
		assertNull(rows(dropped).get(R));
		assertTrue(rows(dropped).containsKey(Q));

		// A later creation drops it too, as it was written before
		assertEquals(0, visible(RowLWW.mergeTable(entry(SCHEMA, 6, Index.none()), dropped)));
	}

	/** Rows delivered without their entry merge as the same rows in a whole entry would. */
	@Test
	public void testRowDeliveryMergesAsWholeEntry() {
		Index<ABlob, AVector<ACell>> stale = Index.of(R, row("old", 2));
		Index<ABlob, AVector<ACell>> fresh = Index.of(Q, row("new", 7));
		AVector<ACell> live = entry(SCHEMA, 4, Index.none());
		AVector<ACell> dropped = SQLTable.createTombstoneState(CVMLong.create(4));
		for (AVector<ACell> own : List.of(live, dropped)) {
			for (Index<ABlob, AVector<ACell>> delivered : List.of(stale, fresh)) {
				assertEquals(rows(RowLWW.mergeTable(own, entry(SCHEMA, 1, delivered))),
					rows(RowLWW.mergeTable(own, rowsOnly(delivered))));
			}
		}
		assertEquals(1, visible(RowLWW.mergeTable(live, rowsOnly(fresh))));
		assertEquals(0, visible(RowLWW.mergeTable(live, rowsOnly(stale))));
		assertNull(RowLWW.mergeTable(null, rowsOnly(fresh)), "an absent table takes nothing");
	}

	/**
	 * A row delivery reads only the delivered rows' paths. Rows elsewhere in the table are
	 * hash-only here and would fail if read, as a persisted table would load them.
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	public void testRowDeliveryDoesNotReadTheRestOfTheTable() {
		ABlob nearKey = Blob.fromHex("ab10");
		Index<ABlob, AVector<ACell>> g1 = Index.of(Blob.fromHex("ab1010"), row("g1", 2));
		Index<ABlob, AVector<ACell>> g2 = Index.of(Blob.fromHex("ab1020"), row("g2", 2));
		Ref[] unread = { notLoaded(g1), notLoaded(g2) };
		Index<ABlob, AVector<ACell>> sibling = Index.unsafeCreate(4, MapEntry.create(nearKey, row("near", 2)),
			unread, (1 << 1) | (1 << 2), 3);
		Index<ABlob, AVector<ACell>> resident = Index.of(Blob.fromHex("ab20"), row("resident", 2));
		Index<ABlob, AVector<ACell>> rows = Index.unsafeCreate(2, MapEntry.create(Blob.fromHex("ab"), row("root", 2)),
			new Ref[] { sibling.getRef(), resident.getRef() }, (1 << 1) | (1 << 2), 5);
		AVector<ACell> table = Vectors.of(SCHEMA, rows, CVMLong.create(1), CVMLong.create(5));

		// A new row, a newer version of a held row, and a deletion
		AVector<ACell> added = RowLWW.mergeTable(table, rowsOnly(Index.of(Blob.fromHex("ab25"), row("new", 3))));
		assertEquals(6, SQLTable.getLiveCount(added));
		AVector<ACell> updated = RowLWW.mergeTable(table, rowsOnly(Index.of(Blob.fromHex("ab20"), row("newer", 3))));
		assertEquals(5, SQLTable.getLiveCount(updated));
		assertEquals(row("newer", 3), rows(updated).get(Blob.fromHex("ab20")));
		AVector<ACell> deleted = RowLWW.mergeTable(table, rowsOnly(Index.of(Blob.fromHex("ab20"), SQLRow.createTombstone(CVMLong.create(3)))));
		assertEquals(4, SQLTable.getLiveCount(deleted));
		// A row from before the table's creation is refused, still without reading the rest
		assertEquals(table, RowLWW.mergeTable(table, rowsOnly(Index.of(Blob.fromHex("ab25"), row("old", 0)))));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static Ref notLoaded(Index<ABlob, AVector<ACell>> node) {
		return RefSoft.createForHash(node.getHash(), NullStore.INSTANCE).markEmbedded(false);
	}

	// ========== Lattice laws ==========

	/**
	 * Associative and idempotent over every combination of a small set of entries whose rows
	 * were written at or after their utime, and commutative unless two entries tie on utime
	 * with different schemas, where the own entry is kept.
	 */
	@Test
	public void testLatticeLaws() {
		List<AVector<ACell>> entries = domain();
		for (AVector<ACell> a : entries) {
			assertEquals(a, RowLWW.mergeTable(a, a));
			for (AVector<ACell> b : entries) {
				AVector<ACell> ab = RowLWW.mergeTable(a, b);
				boolean tie = a.get(SQLTable.POS_UTIME).equals(b.get(SQLTable.POS_UTIME))
					&& !java.util.Objects.equals(a.get(SQLTable.POS_SCHEMA), b.get(SQLTable.POS_SCHEMA));
				if (!tie) assertEquals(ab, RowLWW.mergeTable(b, a));
				for (AVector<ACell> c : entries) {
					assertEquals(RowLWW.mergeTable(ab, c), RowLWW.mergeTable(a, RowLWW.mergeTable(b, c)));
				}
			}
		}
	}

	private static List<AVector<ACell>> domain() {
		List<Index<ABlob, AVector<ACell>>> rowSets = new ArrayList<>();
		for (AVector<ACell> r : new ArrayList<>(java.util.Arrays.asList(null, row("r", 2), row("r", 5)))) {
			for (AVector<ACell> q : new ArrayList<>(java.util.Arrays.asList(null, SQLRow.createTombstone(CVMLong.create(6)), row("q", 7)))) {
				Index<ABlob, AVector<ACell>> rows = Index.none();
				if (r != null) rows = rows.assoc(R, r);
				if (q != null) rows = rows.assoc(Q, q);
				rowSets.add(rows);
			}
		}
		List<AVector<ACell>> entries = new ArrayList<>();
		for (long utime : new long[] {1, 3, 4, 6}) {
			for (Index<ABlob, AVector<ACell>> rows : rowSets) {
				if (!writtenSince(rows, utime)) continue;
				entries.add(entry(SCHEMA, utime, rows));
				entries.add(entry(null, utime, rows));
				if (utime == 4) entries.add(entry(OTHER_SCHEMA, utime, rows));
			}
		}
		return entries;
	}

	private static boolean writtenSince(Index<ABlob, AVector<ACell>> rows, long utime) {
		boolean[] ok = {true};
		rows.forEach((k, v) -> ok[0] &= SQLRow.getTimestamp(v).longValue() >= utime);
		return ok[0];
	}

	private static List<List<AVector<ACell>>> subsetsWithLast(List<AVector<ACell>> xs) {
		List<List<AVector<ACell>>> out = new ArrayList<>();
		int n = xs.size();
		for (int mask = 0; mask < (1 << (n - 1)); mask++) {
			List<AVector<ACell>> subset = new ArrayList<>();
			for (int i = 0; i < n - 1; i++) if ((mask & (1 << i)) != 0) subset.add(xs.get(i));
			subset.add(xs.get(n - 1));
			out.add(subset);
		}
		return out;
	}

	private static List<List<AVector<ACell>>> permutations(List<AVector<ACell>> xs) {
		if (xs.size() <= 1) return List.of(xs);
		List<List<AVector<ACell>>> out = new ArrayList<>();
		for (int i = 0; i < xs.size(); i++) {
			List<AVector<ACell>> rest = new ArrayList<>(xs);
			AVector<ACell> first = rest.remove(i);
			for (List<AVector<ACell>> p : permutations(rest)) {
				List<AVector<ACell>> order = new ArrayList<>();
				order.add(first);
				order.addAll(p);
				out.add(order);
			}
		}
		return out;
	}
}
