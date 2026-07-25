package convex.restapi.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class SseConnectionTest {

	private static final Duration TIMEOUT=Duration.ofSeconds(1);

	@Test
	void slowConnectionDoesNotBlockDeliveryToAnotherConnection() throws Exception {
		CountDownLatch slowWriteStarted=new CountDownLatch(1);
		CountDownLatch releaseSlowWrite=new CountDownLatch(1);
		CountDownLatch fastWriteSeen=new CountDownLatch(1);
		SseConnection slow=new SseConnection(new PrintWriter(
			new BlockingWriter(slowWriteStarted,releaseSlowWrite)));
		SseConnection fast=new SseConnection(new PrintWriter(new LatchingWriter(fastWriteSeen)));

		Thread distributor=Thread.ofVirtual().start(()-> {
			slow.sendEvent("message","slow");
			fast.sendEvent("message","fast");
		});
		try {
			assertTrue(slowWriteStarted.await(TIMEOUT.toMillis(),TimeUnit.MILLISECONDS));
			assertTrue(fastWriteSeen.await(TIMEOUT.toMillis(),TimeUnit.MILLISECONDS));
		} finally {
			releaseSlowWrite.countDown();
			slow.close();
			fast.close();
			distributor.join(TIMEOUT.toMillis());
		}
		assertFalse(distributor.isAlive());
	}

	@Test
	void queueOverflowClosesOnlyTheSlowConnection() throws Exception {
		CountDownLatch slowWriteStarted=new CountDownLatch(1);
		CountDownLatch releaseSlowWrite=new CountDownLatch(1);
		SseConnection slow=new SseConnection(new PrintWriter(
			new BlockingWriter(slowWriteStarted,releaseSlowWrite)),2);
		try {
			slow.sendEvent("message","in-progress");
			assertTrue(slowWriteStarted.await(TIMEOUT.toMillis(),TimeUnit.MILLISECONDS));
			slow.sendEvent("message","queued-1");
			slow.sendEvent("message","queued-2");
			slow.sendEvent("message","overflow");
			assertTrue(slow.isClosed());
		} finally {
			releaseSlowWrite.countDown();
			slow.close();
		}
	}

	@Test
	void dropOldestOverflowKeepsConnectionAndNewestEvents() throws Exception {
		CountDownLatch writeStarted=new CountDownLatch(1);
		CountDownLatch release=new CountDownLatch(1);
		// Wait for the newest event itself, never for a flush count: the dispatcher calls
		// PrintWriter.checkError(), which flushes internally, so each event produces two
		// flushes and a flush tally says nothing about which events have been written.
		GatedCapturingWriter output=new GatedCapturingWriter(writeStarted,release,"data: overflow");
		SseConnection connection=new SseConnection(new PrintWriter(output),2,
			SseConnection.OverflowPolicy.DROP_OLDEST);
		try {
			connection.sendEvent("message","in-progress");
			assertTrue(writeStarted.await(TIMEOUT.toMillis(),TimeUnit.MILLISECONDS));
			// The dispatcher is parked inside the gated write for all three sends, so the
			// queue holds exactly queued-1 and queued-2 when overflow arrives and evicts
			// the oldest. No race: the dispatcher cannot drain while it is blocked.
			connection.sendEvent("message","queued-1");
			connection.sendEvent("message","queued-2");
			connection.sendEvent("message","overflow");
			assertFalse(connection.isClosed());
			release.countDown();
			assertTrue(output.awaitExpected(TIMEOUT));
			String written=output.toString();
			assertTrue(written.contains("data: in-progress"));
			assertFalse(written.contains("data: queued-1"));
			assertTrue(written.contains("data: queued-2"));
			assertFalse(connection.isClosed());
		} finally {
			connection.close();
		}
	}

	@Test
	void writesEventIDsAndFramesEveryDataLine() throws Exception {
		CountDownLatch written=new CountDownLatch(1);
		CapturingWriter output=new CapturingWriter(written);
		SseConnection connection=new SseConnection(new PrintWriter(output));
		try {
			connection.sendEvent("12:3:4","log","first\nid: not-an-id\r\nthird");
			assertTrue(written.await(TIMEOUT.toMillis(),TimeUnit.MILLISECONDS));
			assertEquals("id: 12:3:4\nevent: log\ndata: first\ndata: id: not-an-id\ndata: third\n\n",output.toString());
		} finally {
			connection.close();
		}
	}

	@Test
	void serialisesCommentsAndEventsOnOneDispatcher() throws Exception {
		ContentCapturingWriter output=new ContentCapturingWriter("data: value\n\n");
		SseConnection connection=new SseConnection(new PrintWriter(output));
		try {
			connection.sendComment("connected");
			connection.sendEvent("7","result","value");
			assertTrue(output.awaitExpected(TIMEOUT));
			assertEquals(": connected\n\nid: 7\nevent: result\ndata: value\n\n",output.toString());
		} finally {
			connection.close();
		}
	}

	@Test
	void closeWakesWaiter() throws Exception {
		SseConnection connection=new SseConnection(new PrintWriter(Writer.nullWriter()));
		Thread closer=Thread.ofVirtual().start(connection::close);
		assertTrue(connection.awaitClosed(TIMEOUT.toMillis(),TimeUnit.MILLISECONDS));
		closer.join(TIMEOUT.toMillis());
	}

	private static final class BlockingWriter extends Writer {
		private final CountDownLatch started;
		private final CountDownLatch release;

		private BlockingWriter(CountDownLatch started, CountDownLatch release) {
			this.started=started;
			this.release=release;
		}

		@Override
		public void write(char[] chars, int offset, int length) {
			started.countDown();
			boolean interrupted=false;
			while (true) {
				try {
					release.await();
					break;
				} catch (InterruptedException e) {
					interrupted=true;
				}
			}
			if (interrupted) Thread.currentThread().interrupt();
		}

		@Override public void flush() {}
		@Override public void close() {}
	}

	private static final class LatchingWriter extends Writer {
		private final CountDownLatch written;

		private LatchingWriter(CountDownLatch written) {
			this.written=written;
		}

		@Override
		public void write(char[] chars, int offset, int length) throws IOException {
			written.countDown();
		}

		@Override public void flush() {}
		@Override public void close() {}
	}

	/**
	 * Blocks the first write until released, capturing everything written and signalling
	 * once the expected content has arrived.
	 */
	private static final class GatedCapturingWriter extends Writer {
		private final CountDownLatch firstWriteStarted;
		private final CountDownLatch release;
		private final CountDownLatch expectedSeen=new CountDownLatch(1);
		private final String expected;
		private final StringBuilder builder=new StringBuilder();
		private boolean gated=true;

		private GatedCapturingWriter(CountDownLatch firstWriteStarted, CountDownLatch release,
			String expected) {
			this.firstWriteStarted=firstWriteStarted;
			this.release=release;
			this.expected=expected;
		}

		private boolean awaitExpected(Duration timeout) throws InterruptedException {
			return expectedSeen.await(timeout.toMillis(),TimeUnit.MILLISECONDS);
		}

		@Override
		public void write(char[] chars, int offset, int length) {
			if (gated) {
				gated=false;
				firstWriteStarted.countDown();
				boolean interrupted=false;
				while (true) {
					try {
						release.await();
						break;
					} catch (InterruptedException e) {
						interrupted=true;
					}
				}
				if (interrupted) Thread.currentThread().interrupt();
			}
			synchronized (builder) {
				builder.append(chars,offset,length);
				if (builder.indexOf(expected)>=0) expectedSeen.countDown();
			}
		}

		@Override public void flush() { }
		@Override public void close() {}
		@Override public String toString() {
			synchronized (builder) {
				return builder.toString();
			}
		}
	}

	private static final class CapturingWriter extends Writer {
		private final CountDownLatch written;
		private final StringBuilder builder=new StringBuilder();

		private CapturingWriter(CountDownLatch written) {
			this.written=written;
		}

		@Override
		public synchronized void write(char[] chars, int offset, int length) {
			builder.append(chars,offset,length);
		}

		@Override public void flush() { written.countDown(); }
		@Override public void close() {}
		@Override public synchronized String toString() { return builder.toString(); }
	}

	private static final class ContentCapturingWriter extends Writer {
		private final CountDownLatch expectedSeen=new CountDownLatch(1);
		private final String expected;
		private final StringBuilder builder=new StringBuilder();

		private ContentCapturingWriter(String expected) {
			this.expected=expected;
		}

		@Override
		public synchronized void write(char[] chars, int offset, int length) {
			builder.append(chars,offset,length);
			if (builder.indexOf(expected)>=0) expectedSeen.countDown();
		}

		private boolean awaitExpected(Duration timeout) throws InterruptedException {
			return expectedSeen.await(timeout.toMillis(),TimeUnit.MILLISECONDS);
		}

		@Override public void flush() {}
		@Override public void close() {}
		@Override public synchronized String toString() { return builder.toString(); }
	}
}
