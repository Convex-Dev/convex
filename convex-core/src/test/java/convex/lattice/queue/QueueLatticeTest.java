package convex.lattice.queue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

public class QueueLatticeTest {

	private static final QueueLattice L = QueueLattice.INSTANCE;

	@Test
	public void testZero() {
		AVector<ACell> zero = L.zero();
		assertNotNull(zero);
		assertEquals(QueueLattice.STATE_LENGTH, zero.count());
		assertTrue(QueueLattice.getEntries(zero).isEmpty());
		assertEquals(0L, QueueLattice.getStartOffset(zero));
		assertEquals(0L, QueueLattice.getTimestamp(zero));
	}

	@Test
	public void testMergeNull() {
		AVector<ACell> state = L.zero();
		assertEquals(state, L.merge(state, null));
		assertEquals(state, L.merge(null, state));
	}

	@Test
	public void testMergeIdempotence() {
		AVector<ACell> state = makeState(2, 0, 100);
		AVector<ACell> merged = L.merge(state, state);
		assertEquals(state, merged);
	}

	@Test
	public void testMergeStartOffsetMax() {
		AVector<ACell> a = makeState(3, 0, 100);
		AVector<ACell> b = makeState(3, 5, 200);

		AVector<ACell> merged = L.merge(a, b);
		assertEquals(5L, QueueLattice.getStartOffset(merged));
	}

	@Test
	public void testMergeTimestampMax() {
		AVector<ACell> a = makeState(1, 0, 300);
		AVector<ACell> b = makeState(1, 0, 100);

		AVector<ACell> merged = L.merge(a, b);
		assertEquals(300L, QueueLattice.getTimestamp(merged));
	}

	@Test
	public void testMergeLongerEntriesWin() {
		// a has 5 entries, b has 3 entries (same start offset)
		AVector<ACell> a = makeState(5, 0, 100);
		AVector<ACell> b = makeState(3, 0, 200);

		AVector<ACell> merged = L.merge(a, b);
		AVector<ACell> entries = QueueLattice.getEntries(merged);
		assertEquals(5L, entries.count());
	}

	@Test
	public void testMergeAlignAndTakeLonger() {
		// a: startOffset=0, 10 entries (offsets 0-9)
		// b: startOffset=5, 8 entries (offsets 5-12)
		// merged: startOffset=5, b has 8 entries, a aligned has 5 entries → b wins
		AVector<ACell> a = makeState(10, 0, 100);
		AVector<ACell> b = makeState(8, 5, 200);

		AVector<ACell> merged = L.merge(a, b);
		assertEquals(5L, QueueLattice.getStartOffset(merged));
		assertEquals(8L, QueueLattice.getEntries(merged).count());
	}

	@Test
	public void testMergeTruncationTrumps() {
		// a: startOffset=10, 2 entries (offsets 10-11)
		// b: startOffset=0, 8 entries (offsets 0-7)
		// merged: startOffset=10, a aligned=2, b aligned=0 → a wins
		AVector<ACell> a = makeState(2, 10, 300);
		AVector<ACell> b = makeState(8, 0, 100);

		AVector<ACell> merged = L.merge(a, b);
		assertEquals(10L, QueueLattice.getStartOffset(merged));
		assertEquals(2L, QueueLattice.getEntries(merged).count());
	}

	@Test
	public void testMergeMetadataUnion() {
		AHashMap<ACell, ACell> metaA = Maps.of(
			Keyword.create("name"), Strings.create("q1")
		);
		AHashMap<ACell, ACell> metaB = Maps.of(
			Keyword.create("owner"), Strings.create("alice")
		);

		AVector<ACell> a = Vectors.of(Vectors.empty(), metaA, CVMLong.create(100), CVMLong.ZERO);
		AVector<ACell> b = Vectors.of(Vectors.empty(), metaB, CVMLong.create(100), CVMLong.ZERO);

		AVector<ACell> merged = L.merge(a, b);
		AHashMap<ACell, ACell> mergedMeta = QueueLattice.getMeta(merged);
		assertEquals(Strings.create("q1"), mergedMeta.get(Keyword.create("name")));
		assertEquals(Strings.create("alice"), mergedMeta.get(Keyword.create("owner")));
	}

	@Test
	public void testCheckForeign() {
		assertTrue(L.checkForeign(L.zero()));
		assertFalse(L.checkForeign(null));
		// Too short
		assertFalse(L.checkForeign(Vectors.of(Strings.create("a"))));
		// Missing CVMLong fields
		assertFalse(L.checkForeign(Vectors.of(Vectors.empty(), Maps.empty(), null, null)));
	}

	@Test
	public void testPathReturnsNull() {
		assertNull(L.path(Keyword.create("anything")));
	}

	// ===== Helpers =====

	/**
	 * Creates a queue state with the specified number of entries.
	 */
	private AVector<ACell> makeState(int numEntries, long startOffset, long timestamp) {
		AVector<ACell> entries = Vectors.empty();
		for (int i = 0; i < numEntries; i++) {
			AVector<ACell> entry = QueueEntry.create(
				Strings.create("item-" + (startOffset + i)),
				CVMLong.create(timestamp)
			);
			entries = entries.append(entry);
		}
		return Vectors.of(entries, Maps.empty(), CVMLong.create(timestamp), CVMLong.create(startOffset));
	}
}
