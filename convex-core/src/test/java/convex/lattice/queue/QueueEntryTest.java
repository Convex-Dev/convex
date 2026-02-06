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

public class QueueEntryTest {

	@Test
	public void testCreateFull() {
		CVMLong ts = CVMLong.create(1000);
		AHashMap<ACell, ACell> headers = Maps.of(Keyword.create("source"), Strings.create("test"));
		AVector<ACell> entry = QueueEntry.create(Strings.create("mykey"), Strings.create("myval"), ts, headers);

		assertEquals(Strings.create("mykey"), QueueEntry.getKey(entry));
		assertEquals(Strings.create("myval"), QueueEntry.getValue(entry));
		assertEquals(ts, QueueEntry.getTimestamp(entry));
		assertEquals(headers, QueueEntry.getHeaders(entry));
		assertTrue(QueueEntry.isValid(entry));
	}

	@Test
	public void testCreateValueTimestamp() {
		CVMLong ts = CVMLong.create(2000);
		AVector<ACell> entry = QueueEntry.create(Strings.create("payload"), ts);

		assertNull(QueueEntry.getKey(entry));
		assertEquals(Strings.create("payload"), QueueEntry.getValue(entry));
		assertEquals(ts, QueueEntry.getTimestamp(entry));
		assertNull(QueueEntry.getHeaders(entry));
		assertTrue(QueueEntry.isValid(entry));
	}

	@Test
	public void testCreateWithKey() {
		CVMLong ts = CVMLong.create(3000);
		AVector<ACell> entry = QueueEntry.create(Strings.create("k"), Strings.create("v"), ts);

		assertEquals(Strings.create("k"), QueueEntry.getKey(entry));
		assertEquals(Strings.create("v"), QueueEntry.getValue(entry));
		assertTrue(QueueEntry.isValid(entry));
	}

	@Test
	public void testGettersOnNull() {
		assertNull(QueueEntry.getKey(null));
		assertNull(QueueEntry.getValue(null));
		assertNull(QueueEntry.getTimestamp(null));
		assertNull(QueueEntry.getHeaders(null));
	}

	@Test
	public void testIsValid() {
		assertTrue(QueueEntry.isValid(QueueEntry.create(Strings.create("v"), CVMLong.create(100))));
		assertFalse(QueueEntry.isValid(null));
		// Too short
		assertFalse(QueueEntry.isValid(Vectors.of(Strings.create("a"))));
		// No timestamp
		assertFalse(QueueEntry.isValid(Vectors.of(null, null, null, null)));
	}
}
