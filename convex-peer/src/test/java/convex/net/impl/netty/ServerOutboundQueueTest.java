package convex.net.impl.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Format;
import convex.core.message.Message;
import convex.core.message.MessageType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Replies to inbound connections queue up to a shared byte bound and are
 * released once Netty has written them, so a burst is absorbed rather than
 * dropped while a channel is briefly unwritable.
 */
public class ServerOutboundQueueTest {

	private static Message message(int payloadBytes) {
		Blob b = Blobs.createRandom(payloadBytes);
		return Message.create(MessageType.DATA, b, b.getEncoding());
	}

	private static NettyServerConnection connection(EmbeddedChannel ch, ServerOutboundQueue queue) {
		return new NettyServerConnection(ch, new NettyInboundHandler(m -> null, null), queue);
	}

	private static void awaitDrained(ServerOutboundQueue queue, EmbeddedChannel ch) {
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (queue.getQueuedBytes() > 0) {
			ch.runPendingTasks();
			if (System.nanoTime() > deadline) throw new AssertionError("Queue did not drain: " + queue.getQueuedBytes());
			Thread.onSpinWait();
		}
	}

	@Test
	public void testBoundThenReleaseOnWrite() throws Exception {
		Message m = message(600);
		int size = Format.getVLQCountLength(m.getMessageData().size()) + (int) m.getMessageData().count();
		ServerOutboundQueue queue = new ServerOutboundQueue(size + size / 2, size * 4);
		EmbeddedChannel ch = new EmbeddedChannel(new NettyOutboundHandler());
		try {
			NettyServerConnection conn = connection(ch, queue);

			assertTrue(conn.sendMessage(m));
			assertFalse(conn.sendMessage(m), "second message exceeds the shared bound while the first is in flight");

			awaitDrained(queue, ch);
			assertEquals(0, conn.getOutboundBytes());
			assertTrue(conn.sendMessage(m), "bytes are released once the write completes");
			awaitDrained(queue, ch);

			// Both messages reached the channel, framed as [VLQ length][data]
			for (int i = 0; i < 2; i++) {
				ByteBuf head = ch.readOutbound();
				ByteBuf body = ch.readOutbound();
				assertEquals(Format.getVLQCountLength(m.getMessageData().size()), head.readableBytes());
				byte[] bytes = new byte[body.readableBytes()];
				body.readBytes(bytes);
				assertEquals(m.getMessageData(), Blob.wrap(bytes));
				head.release();
				body.release();
			}
		} finally {
			queue.close();
			ch.finishAndReleaseAll();
		}
	}

	@Test
	public void testConnectionShareOfBound() throws Exception {
		Message m = message(600);
		int size = Format.getVLQCountLength(m.getMessageData().size()) + (int) m.getMessageData().count();
		ServerOutboundQueue queue = new ServerOutboundQueue(size * 10, size + size / 2);
		EmbeddedChannel slow = new EmbeddedChannel(new NettyOutboundHandler());
		EmbeddedChannel other = new EmbeddedChannel(new NettyOutboundHandler());
		try {
			NettyServerConnection slowConn = connection(slow, queue);
			NettyServerConnection otherConn = connection(other, queue);

			assertTrue(slowConn.sendMessage(m));
			assertFalse(slowConn.sendMessage(m), "one connection cannot exceed its share");
			assertTrue(otherConn.sendMessage(m), "other connections still have the shared bound available");
			awaitDrained(queue, slow);
		} finally {
			queue.close();
			slow.finishAndReleaseAll();
			other.finishAndReleaseAll();
		}
	}

	@Test
	public void testClosedChannelRefused() throws Exception {
		Message m = message(100);
		ServerOutboundQueue queue = new ServerOutboundQueue(1 << 20, 1 << 20);
		EmbeddedChannel ch = new EmbeddedChannel(new NettyOutboundHandler());
		try {
			NettyServerConnection conn = connection(ch, queue);
			ch.close().syncUninterruptibly();
			assertFalse(conn.sendMessage(m));
			assertEquals(0, queue.getQueuedBytes());
		} finally {
			queue.close();
			ch.finishAndReleaseAll();
		}
	}
}
