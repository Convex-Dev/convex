package convex.net.impl.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Format;
import convex.core.message.Message;
import convex.core.message.MessageType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Replies to inbound connections pass through one shared queue bounded by the
 * bytes it holds. A channel that stops draining pins only its own small
 * in-flight allowance; client results wait for shared space rather than being
 * dropped; trusted peers are served first and never wait behind client results.
 */
public class ServerOutboundQueueTest {

	/** Holds every write unfinished until {@link #complete()} is called, like a socket nobody reads. */
	static final class StuckHandler extends ChannelOutboundHandlerAdapter {
		final ArrayList<ChannelPromise> held = new ArrayList<>();
		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			held.add(promise);
		}
		void complete() {
			for (ChannelPromise p : held) p.trySuccess();
			held.clear();
		}
	}

	private static Message message(int payloadBytes) {
		Blob b = Blobs.createRandom(payloadBytes);
		return Message.create(MessageType.DATA, b, b.getEncoding());
	}

	/** Bytes the queue accounts for a message: its encoded data, excluding the frame length prefix. */
	private static int size(Message m) {
		return (int) m.getMessageData().count();
	}

	private static NettyServerConnection connection(EmbeddedChannel ch, ServerOutboundQueue queue) {
		return new NettyServerConnection(ch, new NettyInboundHandler(m -> null, null), queue);
	}

	@Test
	public void testSharedBoundReleasedOnHandover() {
		Message m = message(600);
		int size = size(m);
		ServerOutboundQueue queue = new ServerOutboundQueue(size + size / 2, size * 10, false);
		EmbeddedChannel ch = new EmbeddedChannel(new NettyOutboundHandler());
		try {
			NettyServerConnection conn = connection(ch, queue);
			assertTrue(conn.trySendMessage(m));
			assertFalse(conn.trySendMessage(m), "second message exceeds the shared bound while the first is queued");
			assertEquals(size, queue.getQueuedBytes());

			assertEquals(1, queue.drainBatch());
			assertEquals(0, queue.getQueuedBytes(), "bytes leave the shared bound once handed to Netty");
			assertTrue(conn.trySendMessage(m));
			assertEquals(1, queue.drainBatch());

			// Both messages reached the channel in order, framed as [VLQ length][data]
			for (int i = 0; i < 2; i++) {
				ByteBuf head = ch.readOutbound();
				ByteBuf body = ch.readOutbound();
				assertEquals(Format.getVLQCountLength(size), head.readableBytes());
				byte[] bytes = new byte[body.readableBytes()];
				body.readBytes(bytes);
				assertEquals(m.getMessageData(), Blob.wrap(bytes));
				head.release();
				body.release();
			}
			assertEquals(0, conn.getPendingBytes(), "written bytes are no longer pending");
		} finally {
			queue.close();
			ch.finishAndReleaseAll();
		}
	}

	@Test
	public void testBlockingSendWaitsForSharedSpace() throws Exception {
		Message m = message(600);
		int size = size(m);
		ServerOutboundQueue queue = new ServerOutboundQueue(size + size / 2, size * 10, false);
		EmbeddedChannel ch = new EmbeddedChannel(new NettyOutboundHandler());
		try {
			NettyServerConnection conn = connection(ch, queue);
			assertTrue(conn.trySendMessage(m));

			// A blocking send waits until the writer frees the shared bound. Hand over
			// exactly one entry: the freed space lets the blocked send enqueue at any
			// moment after that, so a whole-batch drain could pick it up too.
			CompletableFuture<Boolean> blocked = CompletableFuture.supplyAsync(() -> conn.sendMessage(m));
			assertEquals(1, queue.drainBatch(1));
			assertTrue(blocked.get(5, TimeUnit.SECONDS), "blocked send proceeds once space is freed");
			assertEquals(size, queue.getQueuedBytes());
			assertEquals(1, queue.drainBatch(1));

			// A message that can never fit is refused without waiting
			assertFalse(conn.sendMessage(message(size * 2)));
		} finally {
			queue.close();
			ch.finishAndReleaseAll();
		}
	}

	@Test
	public void testStuckChannelPinsOnlyItsOwnAllowance() {
		Message m = message(600);
		int size = size(m);
		ServerOutboundQueue queue = new ServerOutboundQueue(size * 10, size + size / 2, false);
		StuckHandler stuck = new StuckHandler();
		EmbeddedChannel slow = new EmbeddedChannel(stuck);
		EmbeddedChannel other = new EmbeddedChannel(new NettyOutboundHandler());
		try {
			NettyServerConnection slowConn = connection(slow, queue);
			NettyServerConnection otherConn = connection(other, queue);

			// Two replies for the stuck reader are handed to Netty but never written
			assertTrue(slowConn.trySendMessage(m));
			assertTrue(slowConn.trySendMessage(m));
			assertEquals(2, queue.drainBatch());
			assertEquals(0, queue.getQueuedBytes(), "the shared queue holds nothing for the stuck reader");
			assertEquals(2 * size, slowConn.getPendingBytes());

			assertFalse(slowConn.trySendMessage(m), "over its cap, the stuck reader's further replies are refused");
			long start = System.nanoTime();
			assertFalse(slowConn.sendMessage(m), "a blocking send never waits for a stalled reader");
			assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
			assertTrue(otherConn.trySendMessage(m), "other connections are unaffected");
			assertEquals(1, queue.drainBatch());
			assertEquals(0, otherConn.getPendingBytes());

			stuck.complete();
			assertEquals(0, slowConn.getPendingBytes(), "pending bytes are released once the writes complete");
			assertTrue(slowConn.trySendMessage(m));
		} finally {
			queue.close();
			slow.finishAndReleaseAll();
			other.finishAndReleaseAll();
		}
	}

	@Test
	public void testTrustedPeerServedFirstAndNeverWaits() {
		Message m = message(600);
		int size = size(m);
		ServerOutboundQueue queue = new ServerOutboundQueue(size + size / 2, size * 10, false);
		EmbeddedChannel client = new EmbeddedChannel(new NettyOutboundHandler());
		EmbeddedChannel peer = new EmbeddedChannel(new NettyOutboundHandler());
		try {
			NettyServerConnection clientConn = connection(client, queue);
			NettyServerConnection peerConn = connection(peer, queue);
			peerConn.setTrustedKey(AKeyPair.createSeeded(7).getAccountKey());

			assertTrue(clientConn.trySendMessage(m));
			assertFalse(clientConn.trySendMessage(m), "shared bound is full");
			assertTrue(peerConn.trySendMessage(m), "a trusted peer is exempt from the shared bound");
			assertTrue(peerConn.sendMessage(m), "and never waits for it");

			// The writer hands the trusted peer's replies over before the client's
			assertEquals(1, queue.drainBatch(1));
			assertTrue(peer.outboundMessages().size() > 0);
			assertEquals(0, client.outboundMessages().size());
			assertEquals(2, queue.drainBatch());
			assertTrue(client.outboundMessages().size() > 0);
		} finally {
			queue.close();
			client.finishAndReleaseAll();
			peer.finishAndReleaseAll();
		}
	}

	@Test
	public void testClosedChannelRefused() {
		Message m = message(100);
		ServerOutboundQueue queue = new ServerOutboundQueue(1 << 20, 1 << 20, false);
		EmbeddedChannel ch = new EmbeddedChannel(new NettyOutboundHandler());
		try {
			NettyServerConnection conn = connection(ch, queue);
			ch.close().syncUninterruptibly();
			assertFalse(conn.trySendMessage(m));
			assertFalse(conn.sendMessage(m));
			assertEquals(0, queue.getQueuedBytes());
		} finally {
			queue.close();
			ch.finishAndReleaseAll();
		}
	}
}
