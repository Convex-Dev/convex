package convex.net.impl.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

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
 * bytes it holds; a channel that stops draining pins only its own small
 * in-flight allowance and never the shared bound or other connections.
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
	private static int frameSize(Message m) {
		return (int) m.getMessageData().count();
	}

	private static NettyServerConnection connection(EmbeddedChannel ch, ServerOutboundQueue queue) {
		return new NettyServerConnection(ch, new NettyInboundHandler(m -> null, null), queue);
	}

	@Test
	public void testSharedBoundReleasedOnHandover() {
		Message m = message(600);
		int size = frameSize(m);
		ServerOutboundQueue queue = new ServerOutboundQueue(size + size / 2, size * 10, false);
		EmbeddedChannel ch = new EmbeddedChannel(new NettyOutboundHandler());
		try {
			NettyServerConnection conn = connection(ch, queue);
			assertTrue(conn.sendMessage(m));
			assertFalse(conn.sendMessage(m), "second message exceeds the shared bound while the first is queued");
			assertEquals(size, queue.getQueuedBytes());

			assertEquals(1, queue.drainBatch());
			assertEquals(0, queue.getQueuedBytes(), "bytes leave the shared bound once handed to Netty");
			assertTrue(conn.sendMessage(m));
			assertEquals(1, queue.drainBatch());

			// Both messages reached the channel in order, framed as [VLQ length][data]
			for (int i = 0; i < 2; i++) {
				ByteBuf head = ch.readOutbound();
				ByteBuf body = ch.readOutbound();
				assertEquals(Format.getVLQCountLength((int) m.getMessageData().count()), head.readableBytes());
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
	public void testStuckChannelPinsOnlyItsOwnAllowance() {
		Message m = message(600);
		int size = frameSize(m);
		ServerOutboundQueue queue = new ServerOutboundQueue(size * 10, size + size / 2, false);
		StuckHandler stuck = new StuckHandler();
		EmbeddedChannel slow = new EmbeddedChannel(stuck);
		EmbeddedChannel other = new EmbeddedChannel(new NettyOutboundHandler());
		try {
			NettyServerConnection slowConn = connection(slow, queue);
			NettyServerConnection otherConn = connection(other, queue);

			// Two replies for the stuck reader are handed to Netty but never written
			assertTrue(slowConn.sendMessage(m));
			assertTrue(slowConn.sendMessage(m));
			assertEquals(2, queue.drainBatch());
			assertEquals(0, queue.getQueuedBytes(), "the shared queue holds nothing for the stuck reader");
			assertEquals(2 * size, slowConn.getPendingBytes());

			assertFalse(slowConn.sendMessage(m), "over its cap, the stuck reader's further replies are refused");
			assertTrue(otherConn.sendMessage(m), "other connections are unaffected");
			assertEquals(1, queue.drainBatch());
			assertEquals(0, otherConn.getPendingBytes());

			stuck.complete();
			assertEquals(0, slowConn.getPendingBytes(), "pending bytes are released once the writes complete");
			assertTrue(slowConn.sendMessage(m));
		} finally {
			queue.close();
			slow.finishAndReleaseAll();
			other.finishAndReleaseAll();
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
			assertFalse(conn.sendMessage(m));
			assertEquals(0, queue.getQueuedBytes());
		} finally {
			queue.close();
			ch.finishAndReleaseAll();
		}
	}
}
