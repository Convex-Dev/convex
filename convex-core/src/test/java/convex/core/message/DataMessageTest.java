package convex.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import convex.core.cpos.CPoSConstants;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;

/**
 * DATA messages carry their cells in a Vector. Once that Vector holds more than
 * one chunk it is a tree, and its chunk leaves must travel in the message too or
 * the receiver cannot read any element, not even the DATA tag.
 */
public class DataMessageTest {

	private static List<ACell> cells(int n) {
		ArrayList<ACell> cells = new ArrayList<>();
		for (int i = 0; i < n; i++) cells.add(Blobs.createRandom(200));
		return cells;
	}

	private static void assertRoundTrip(Message sent, List<ACell> cells) throws BadFormatException {
		Message received = Message.create(sent.getMessageData());
		AVector<?> payload = received.getPayload(null); // storeless: everything must be in the message
		assertEquals(MessageType.DATA, received.getType());
		assertEquals(cells.size() + 1, payload.count());
		assertEquals(MessageTag.DATA, payload.get(0));
		for (int i = 0; i < cells.size(); i++) assertEquals(cells.get(i), payload.get(i + 1));
	}

	@Test
	public void testDataMessageBeyondOneChunk() throws BadFormatException {
		// No cell-count limit: only the encoded size bounds a DATA message
		for (int n : new int[] { 1, Vectors.CHUNK_SIZE - 1, Vectors.CHUNK_SIZE, Vectors.CHUNK_SIZE + 1, 100, 1000 }) {
			List<ACell> cells = cells(n);
			Message m = Message.createDataMessage(cells, 1 << 20);
			assertRoundTrip(m, cells);
		}
	}

	/** Each message within the limit and readable alone; every cell carried once, in order. */
	private static void assertPartitioned(List<ACell> cells, int limit) throws BadFormatException {
		List<Message> messages = Message.createDataMessages(cells, limit);
		assertTrue(messages.size() > 1);
		int seen = 0;
		for (Message m : messages) {
			Blob data = m.getMessageData();
			assertTrue(data.count() <= limit);
			AVector<?> payload = Message.create(data).getPayload(null);
			assertEquals(MessageTag.DATA, payload.get(0));
			for (int i = 1; i < payload.count(); i++) assertEquals(cells.get(seen++), payload.get(i));
		}
		assertEquals(cells.size(), seen);
	}

	@Test
	public void testDataMessagesPartitionedBeyondOneChunk() throws BadFormatException {
		assertPartitioned(cells(300), 64 * 1024);
	}

	@Test
	public void testManyCellsPartitioned() throws BadFormatException {
		// Thousands of cells per message at the default Belief delta size
		assertPartitioned(cells(30000), 4 * 1024 * 1024);
	}

	@Test
	public void testCellOverheadBoundsMessageLength() {
		// Partitioning trusts this bound, so it must never fall below a message's exact
		// length: for small cells, at any count, and across chunk and level boundaries
		TreeSet<Integer> counts = new TreeSet<>();
		for (int n = 1; n <= 300; n++) counts.add(n); // every count through the first chunks and level
		for (int level : new int[] { 256, 4096 }) {
			for (int n = level - 20; n <= level + 20; n++) counts.add(n);
		}
		Random r = new Random(1234);
		ArrayList<ACell> cells = new ArrayList<>();
		long bound = 0;
		for (int n = 1; n <= counts.last(); n++) {
			ACell cell = Blobs.createRandom(r, 141 + r.nextInt(260)); // just over the embedding limit
			cells.add(cell);
			bound += cell.getEncodingLength() + Message.DATA_CELL_OVERHEAD;
			if (!counts.contains(n)) continue;
			long exact = Message.createDataMessage(cells, (int) CPoSConstants.MAX_MESSAGE_LENGTH).getMessageData().count();
			assertTrue(bound >= exact, "Bound " + bound + " below exact length " + exact + " for " + n + " cells");
		}
	}
}
