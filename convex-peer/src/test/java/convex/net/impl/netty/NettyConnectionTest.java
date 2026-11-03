package convex.net.impl.netty;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import convex.core.data.Blobs;
import convex.core.data.Format;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.util.ThreadUtils;
import convex.peer.Config;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;

/** Outbound flushing of the Netty client connection. */
public class NettyConnectionTest {

	/**
	 * A message queued just before close is written in full once its flush completes,
	 * where close alone discards whatever the event loop has not yet written (#778).
	 */
	@Test public void testFlushWritesQueuedMessageBeforeClose() throws Exception {
		try (ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
			CompletableFuture<Long> received=CompletableFuture.supplyAsync(
				() -> readUntilClosed(server),ThreadUtils.getVirtualExecutor());
			NettyConnection client=NettyConnection.connect(server.getLocalSocketAddress(),m -> {});
			try {
				// Far larger than socket buffers, so writing it takes many event loop passes
				Message message=Message.create(MessageType.DATA,
					Vectors.of(Blobs.createRandom(new Random(778),8_000_000)));
				long length=message.getMessageData().count();
				assertTrue(client.trySendMessage(message));

				client.flushOutbound().get(10,TimeUnit.SECONDS);
				client.close();
				assertEquals(Format.getVLQCountLength(length)+length,received.get(10,TimeUnit.SECONDS));
			} finally {
				client.close();
			}
		}
	}

	/** An idle connection has nothing to flush; a closed one cannot report written messages. */
	@Test public void testFlushCompletesWhenIdleAndFailsWhenClosed() throws Exception {
		try (ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
			CompletableFuture<Long> received=CompletableFuture.supplyAsync(
				() -> readUntilClosed(server),ThreadUtils.getVirtualExecutor());
			NettyConnection client=NettyConnection.connect(server.getLocalSocketAddress(),m -> {});
			client.flushOutbound().get(10,TimeUnit.SECONDS);
			client.close();

			ExecutionException failure=assertThrows(ExecutionException.class,
				() -> client.flushOutbound().get(10,TimeUnit.SECONDS));
			assertInstanceOf(IOException.class,failure.getCause());
			assertEquals(0L,received.get(10,TimeUnit.SECONDS));
		}
	}

	/** Close returns at once while the connection's event loop is busy delivering a message. */
	@Test
	@Timeout(60)
	public void testCloseDoesNotWaitForBusyEventLoop() throws Exception {
		try (NettyServer server=new NettyServer(0)) {
			server.setReceiveAction(NettyConnectionTest::replyOne);
			server.launch();
			CountDownLatch delivering=new CountDownLatch(1);
			CountDownLatch release=new CountDownLatch(1);
			NettyConnection client=NettyConnection.connect(server.getHostAddress(),m -> {
				delivering.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			try {
				assertTrue(client.trySendMessage(Message.createPing(1)));
				delivering.await();

				// Close on another thread, so a timeout interrupts this one and the loop is released
				Thread closer=Thread.ofVirtual().start(client::close);
				closer.join();
				assertTrue(client.isClosed());
			} finally {
				release.countDown();
			}
		}
	}

	/**
	 * On its event loop a send to a full queue fails at once: that loop is what empties
	 * the queue, so a wait for space could only time out.
	 */
	@Test
	@Timeout(60)
	public void testSendOnEventLoopDoesNotWait() throws Exception {
		record Outcome(boolean full, boolean sent, long nanos) {}
		try (NettyServer server=new NettyServer(0)) {
			server.setReceiveAction(NettyConnectionTest::replyOne);
			server.launch();
			CompletableFuture<NettyConnection> self=new CompletableFuture<>();
			CompletableFuture<Outcome> outcome=new CompletableFuture<>();
			NettyConnection client=NettyConnection.connect(server.getHostAddress(),m -> {
				if (outcome.isDone()) return;
				NettyConnection c=self.join();
				c.setOutboundLimits(1,Long.MAX_VALUE);
				boolean full=c.trySendMessage(Message.createPing(2)) && !c.trySendMessage(Message.createPing(3));
				long start=System.nanoTime();
				boolean sent=c.sendMessage(Message.createPing(4));
				outcome.complete(new Outcome(full,sent,System.nanoTime()-start));
			});
			try {
				self.complete(client);
				assertTrue(client.trySendMessage(Message.createPing(1)));
				Outcome result=outcome.get();
				assertTrue(result.full(),"Nothing leaves the queue while its event loop is busy");
				assertFalse(result.sent());
				// A wait would have lasted the whole timeout
				assertTrue(result.nanos()<TimeUnit.MILLISECONDS.toNanos(Config.DEFAULT_INTERNAL_TIMEOUT));
			} finally {
				client.close();
			}
		}
	}

	/** Any loop of the shared group counts, not only the one serving a given channel. */
	@Test
	public void testInEventLoopCoversWholeGroup() throws Exception {
		EventLoopGroup group=NettyConnection.getEventLoopGroup();
		assertFalse(NettyConnection.inEventLoop(group));
		for (EventExecutor loop : group) {
			assertTrue(loop.submit(() -> NettyConnection.inEventLoop(group)).get());
		}
	}

	/** Replies to a request with a result, as a peer would. */
	private static void replyOne(Message m) {
		try {
			m.getPayload(null);
		} catch (BadFormatException e) {
			throw new AssertionError(e);
		}
		m.returnMessage(Message.createResult(m.getRequestID(),CVMLong.ONE,null));
	}

	/** Accepts one connection and counts its bytes until the client closes it. */
	private static long readUntilClosed(ServerSocket server) {
		try (Socket socket=server.accept(); InputStream in=socket.getInputStream()) {
			byte[] buffer=new byte[65536];
			long total=0;
			for (int n; (n=in.read(buffer))>=0;) total+=n;
			return total;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
