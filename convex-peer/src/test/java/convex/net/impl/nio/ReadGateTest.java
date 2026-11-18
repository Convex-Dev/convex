package convex.net.impl.nio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.message.Message;
import convex.net.ingress.Ingress;
import convex.net.ingress.IngressLimits;

/**
 * A read gate on a real socket and selector. The test thread stands in for the
 * selector thread; waits are on latches, never on time.
 */
public class ReadGateTest {

	private static final long WAIT = 10;

	/** One worker, and a connection pauses at two waiting messages. */
	private static final IngressLimits LIMITS = new IngressLimits(1, 0, 2, 1_000_000, 10_000_000, 1_000_000,
		1_000_000, 1, 10_000);

	/**
	 * A gate on the accepted end of a socket pair, whose first routed message waits
	 * for {@link #release}. One per test: test methods run concurrently on one instance.
	 */
	private static final class Fixture implements AutoCloseable {
		final Selector selector = Selector.open();
		final ServerSocketChannel listener = ServerSocketChannel.open();
		final SocketChannel client;
		final SocketChannel served;
		final SelectionKey key;
		final Ingress ingress = new Ingress("test", LIMITS, c -> false, e -> {});
		final ReadGate gate;

		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch resumed = new CountDownLatch(1);
		final List<Message> routed = new CopyOnWriteArrayList<>();
		final Semaphore routedCount = new Semaphore(0);

		Fixture() throws IOException {
			listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			client = SocketChannel.open(listener.getLocalAddress());
			served = listener.accept();
			served.configureBlocking(false);
			key = served.register(selector, SelectionKey.OP_READ);
			gate = new ReadGate(served, selector);
			Connection conn = Connection.create(served, gate, null);
			gate.setChannel(ingress.register(conn, m -> {
				routed.add(m);
				routedCount.release();
				entered.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return null;
			}, () -> {
				gate.resume();
				resumed.countDown();
			}));
		}

		/** Admits messages, as the selector thread does, until the ingress asks to pause. */
		List<Message> admitUntilPause() throws InterruptedException {
			List<Message> sent = new ArrayList<>();
			for (int i = 0; !gate.isPauseAsked(); i++) {
				assertTrue(i < 3, "ingress must ask to pause at its message limit");
				Message m = Message.create(gate.getChannel().getConnection(), Blob.wrap(new byte[] {(byte) i}));
				gate.accept(m);
				sent.add(m);
				// The worker holds the first message, so the rest wait behind it
				if (i == 0) assertTrue(entered.await(WAIT, TimeUnit.SECONDS));
			}
			return sent;
		}

		boolean reading() {
			return (key.interestOps() & SelectionKey.OP_READ) != 0;
		}

		/** Messages routed once the given number have been, in order. */
		List<Message> waitRouted(int n) throws InterruptedException {
			assertTrue(routedCount.tryAcquire(n, WAIT, TimeUnit.SECONDS), "every admitted message is routed");
			return routed;
		}

		@Override
		public void close() throws IOException {
			// Before closing the ingress, which waits for its worker's turn to end
			release.countDown();
			ingress.close();
			served.close();
			client.close();
			listener.close();
			selector.close();
		}
	}

	@Test
	public void testPauseStopsReadingUntilResumed() throws Exception {
		try (Fixture f = new Fixture()) {
			List<Message> sent = f.admitUntilPause();
			assertTrue(f.reading(), "reading continues until the read ends");

			f.gate.afterRead();
			assertFalse(f.reading(), "a paused connection is not read");
			assertFalse(f.gate.isPauseAsked());

			f.release.countDown();
			assertTrue(f.resumed.await(WAIT, TimeUnit.SECONDS));
			assertTrue(f.reading(), "resume reads the connection again");
			assertEquals(sent, f.waitRouted(sent.size()));
		}
	}

	@Test
	public void testResumeBeforeReadEndsKeepsReading() throws Exception {
		try (Fixture f = new Fixture()) {
			List<Message> sent = f.admitUntilPause();

			// The ingress drains and resumes before the selector thread finishes its read
			f.release.countDown();
			assertTrue(f.resumed.await(WAIT, TimeUnit.SECONDS));
			f.gate.afterRead();
			assertTrue(f.reading(), "a pause already resumed must not stop reading");
			assertEquals(sent, f.waitRouted(sent.size()));
		}
	}
}
