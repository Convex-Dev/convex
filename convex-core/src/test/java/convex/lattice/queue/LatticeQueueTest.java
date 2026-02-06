package convex.lattice.queue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

public class LatticeQueueTest {

	// ===== Empty Queue =====

	@Test
	public void testEmptyQueue() {
		LatticeQueue q = LatticeQueue.create();
		assertTrue(q.isEmpty());
		assertEquals(0L, q.size());
		assertEquals(0L, q.startOffset());
		assertEquals(0L, q.endOffset());
		assertNull(q.peekFirst());
		assertNull(q.peekLast());
		assertNull(q.peekFirstEntry());
		assertNull(q.peekLastEntry());
		assertNull(q.peek(0));
		assertNull(q.peekEntry(0));
	}

	// ===== Offer =====

	@Test
	public void testOfferSingle() {
		LatticeQueue q = LatticeQueue.create();
		long offset = q.offer(Strings.create("hello"));
		assertEquals(0L, offset);
		assertEquals(1L, q.size());
		assertFalse(q.isEmpty());
		assertEquals(Strings.create("hello"), q.peek(0));
	}

	@Test
	public void testOfferSequential() {
		LatticeQueue q = LatticeQueue.create();
		assertEquals(0L, q.offer(Strings.create("a")));
		assertEquals(1L, q.offer(Strings.create("b")));
		assertEquals(2L, q.offer(Strings.create("c")));

		assertEquals(3L, q.size());
		assertEquals(0L, q.startOffset());
		assertEquals(3L, q.endOffset());

		assertEquals(Strings.create("a"), q.peek(0));
		assertEquals(Strings.create("b"), q.peek(1));
		assertEquals(Strings.create("c"), q.peek(2));
	}

	@Test
	public void testOfferNullThrows() {
		LatticeQueue q = LatticeQueue.create();
		assertThrows(IllegalArgumentException.class, () -> q.offer(null));
	}

	@Test
	public void testOfferWithKey() {
		LatticeQueue q = LatticeQueue.create();
		q.offer(Strings.create("mykey"), Strings.create("myval"));

		AVector<ACell> entry = q.peekEntry(0);
		assertNotNull(entry);
		assertEquals(Strings.create("mykey"), QueueEntry.getKey(entry));
		assertEquals(Strings.create("myval"), QueueEntry.getValue(entry));
		assertNotNull(QueueEntry.getTimestamp(entry));
	}

	@Test
	public void testOfferWithHeaders() {
		LatticeQueue q = LatticeQueue.create();
		AHashMap<ACell, ACell> headers = Maps.of(
			Keyword.create("source"), Strings.create("test")
		);
		q.offer(Strings.create("k"), Strings.create("v"), headers);

		AVector<ACell> entry = q.peekEntry(0);
		AHashMap<ACell, ACell> h = QueueEntry.getHeaders(entry);
		assertNotNull(h);
		assertEquals(Strings.create("test"), h.get(Keyword.create("source")));
	}

	// ===== Peek =====

	@Test
	public void testPeekMissing() {
		LatticeQueue q = LatticeQueue.create();
		q.offer(Strings.create("a"));
		assertNull(q.peek(99));
		assertNull(q.peek(-1));
	}

	@Test
	public void testPeekFirstLast() {
		LatticeQueue q = LatticeQueue.create();
		q.offer(Strings.create("first"));
		q.offer(Strings.create("middle"));
		q.offer(Strings.create("last"));

		assertEquals(Strings.create("first"), q.peekFirst());
		assertEquals(Strings.create("last"), q.peekLast());
	}

	@Test
	public void testPeekFirstLastSingle() {
		LatticeQueue q = LatticeQueue.create();
		q.offer(Strings.create("only"));
		assertEquals(Strings.create("only"), q.peekFirst());
		assertEquals(Strings.create("only"), q.peekLast());
	}

	// ===== Range =====

	@Test
	public void testRangeEmpty() {
		LatticeQueue q = LatticeQueue.create();
		assertTrue(q.range(0, 10).isEmpty());
	}

	@Test
	public void testRangeExact() {
		LatticeQueue q = LatticeQueue.create();
		q.offer(Strings.create("a"));
		q.offer(Strings.create("b"));
		q.offer(Strings.create("c"));

		AVector<ACell> r = q.range(0, 2);
		assertEquals(3L, r.count());
		assertEquals(Strings.create("a"), r.get(0));
		assertEquals(Strings.create("b"), r.get(1));
		assertEquals(Strings.create("c"), r.get(2));
	}

	@Test
	public void testRangePartial() {
		LatticeQueue q = LatticeQueue.create();
		q.offer(Strings.create("a"));
		q.offer(Strings.create("b"));
		q.offer(Strings.create("c"));

		AVector<ACell> r = q.range(1, 2);
		assertEquals(2L, r.count());
		assertEquals(Strings.create("b"), r.get(0));
		assertEquals(Strings.create("c"), r.get(1));
	}

	@Test
	public void testRangeOutOfBounds() {
		LatticeQueue q = LatticeQueue.create();
		q.offer(Strings.create("a"));

		assertTrue(q.range(10, 20).isEmpty());
		assertTrue(q.range(-10, -5).isEmpty());
	}

	@Test
	public void testRangeInverted() {
		LatticeQueue q = LatticeQueue.create();
		q.offer(Strings.create("a"));
		assertTrue(q.range(5, 2).isEmpty());
	}

	// ===== Metadata =====

	@Test
	public void testMetadata() {
		LatticeQueue q = LatticeQueue.create();
		assertNull(q.getMeta(Keyword.create("name")));

		q.setMeta(Keyword.create("name"), Strings.create("myqueue"));
		assertEquals(Strings.create("myqueue"), q.getMeta(Keyword.create("name")));

		q.setMeta(Keyword.create("owner"), Strings.create("alice"));
		assertEquals(Strings.create("myqueue"), q.getMeta(Keyword.create("name")));
		assertEquals(Strings.create("alice"), q.getMeta(Keyword.create("owner")));
	}

	// ===== Truncation =====

	@Test
	public void testTruncateEmpty() {
		LatticeQueue q = LatticeQueue.create();
		assertEquals(0L, q.truncate(10));
		assertEquals(10L, q.startOffset());
		assertEquals(10L, q.endOffset());
		assertTrue(q.isEmpty());
	}

	@Test
	public void testTruncateNoOp() {
		LatticeQueue q = LatticeQueue.create();
		q.offer(Strings.create("a"));
		q.offer(Strings.create("b"));

		assertEquals(0L, q.truncate(0));
		assertEquals(2L, q.size());
	}

	@Test
	public void testTruncatePartial() {
		LatticeQueue q = LatticeQueue.create();
		q.offer(Strings.create("a")); // offset 0
		q.offer(Strings.create("b")); // offset 1
		q.offer(Strings.create("c")); // offset 2

		assertEquals(2L, q.truncate(2));
		assertEquals(2L, q.startOffset());
		assertEquals(3L, q.endOffset());
		assertEquals(1L, q.size());

		// offset 0 and 1 no longer accessible
		assertNull(q.peek(0));
		assertNull(q.peek(1));
		// offset 2 still valid
		assertEquals(Strings.create("c"), q.peek(2));
	}

	@Test
	public void testTruncateAll() {
		LatticeQueue q = LatticeQueue.create();
		q.offer(Strings.create("a"));
		q.offer(Strings.create("b"));

		assertEquals(2L, q.truncate(10));
		assertTrue(q.isEmpty());
		assertEquals(10L, q.startOffset());
	}

	@Test
	public void testOfferAfterTruncate() {
		LatticeQueue q = LatticeQueue.create();
		q.offer(Strings.create("a")); // offset 0
		q.offer(Strings.create("b")); // offset 1
		q.truncate(2);

		long offset = q.offer(Strings.create("c"));
		assertEquals(2L, offset);
		assertEquals(Strings.create("c"), q.peek(2));
		assertEquals(1L, q.size());
	}

	// ===== Fork / Sync =====

	@Test
	public void testForkSync() {
		LatticeQueue root = LatticeQueue.create();
		root.offer(Strings.create("original"));

		LatticeQueue fork = root.fork();
		fork.offer(Strings.create("from-fork"));

		// Root doesn't see fork changes yet
		assertEquals(1L, root.size());

		fork.sync();

		// After sync, root sees fork changes
		assertEquals(2L, root.size());
		assertEquals(Strings.create("from-fork"), root.peek(1));
	}

	@Test
	public void testForkSyncPreservesMetadata() {
		LatticeQueue root = LatticeQueue.create();
		root.setMeta(Keyword.create("name"), Strings.create("q1"));

		LatticeQueue fork = root.fork();
		fork.setMeta(Keyword.create("owner"), Strings.create("bob"));
		fork.sync();

		assertEquals(Strings.create("q1"), root.getMeta(Keyword.create("name")));
		assertEquals(Strings.create("bob"), root.getMeta(Keyword.create("owner")));
	}

	@Test
	public void testForkSyncLongerWins() {
		LatticeQueue root = LatticeQueue.create();
		root.offer(Strings.create("base"));

		LatticeQueue fork1 = root.fork();
		LatticeQueue fork2 = root.fork();

		// fork1 appends 3 items, fork2 appends 1 item
		fork1.offer(Strings.create("f1-a"));
		fork1.offer(Strings.create("f1-b"));
		fork1.offer(Strings.create("f1-c"));

		fork2.offer(Strings.create("f2-a"));

		fork1.sync();
		fork2.sync();

		// fork1's entries should win (longer)
		assertEquals(4L, root.size());
		assertEquals(Strings.create("f1-c"), root.peek(3));
	}

	// ===== Cursor Access =====

	@Test
	public void testCursorAccess() {
		LatticeQueue q = LatticeQueue.create();
		assertNotNull(q.cursor());
	}

	// ===== Entry Timestamps =====

	@Test
	public void testEntryHasTimestamp() {
		LatticeQueue q = LatticeQueue.create();
		long before = System.currentTimeMillis();
		q.offer(Strings.create("timed"));
		long after = System.currentTimeMillis();

		AVector<ACell> entry = q.peekEntry(0);
		CVMLong ts = QueueEntry.getTimestamp(entry);
		assertNotNull(ts);
		assertTrue(ts.longValue() >= before);
		assertTrue(ts.longValue() <= after);
	}
}
