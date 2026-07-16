package convex.restapi.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
