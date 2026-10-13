package convex.net.impl.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.message.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

public class NettyInboundHandlerTest {

	private static final int LIMIT = 100;

	private static ByteBuf frame(byte[] body) {
		int headLen = Format.getVLQCountLength(body.length);
		byte[] bytes = new byte[headLen + body.length];
		Format.writeVLQCount(bytes, 0, body.length);
		System.arraycopy(body, 0, bytes, headLen, body.length);
		return Unpooled.wrappedBuffer(bytes);
	}

	/**
	 * A frame whose declared length exceeds the limit is a fatal framing error: the
	 * channel must close and NOTHING further may be delivered from it. The body here is
	 * crafted so that, read as a stream of frames, it is 20 well-formed 9-byte "messages".
	 */
	@Test
	public void testOversizedFrameBodyIsNotReinterpretedAsMessages() {
		List<Message> delivered = new ArrayList<>();
		NettyInboundHandler handler = new NettyInboundHandler(m -> {
			delivered.add(m);
			return null;
		}, null, LIMIT);
		EmbeddedChannel ch = new EmbeddedChannel(handler);

		byte[] body = new byte[200];
		for (int i = 0; i < body.length; i += 10) {
			body[i] = 9;
			for (int j = 1; j < 10; j++) body[i + j] = (byte) 0x31;
		}

		ch.writeInbound(frame(body));
		ch.runPendingTasks();

		assertFalse(ch.isOpen(), "an oversized frame must close the channel");
		assertTrue(delivered.isEmpty(),
			"bytes from inside a rejected oversized frame were delivered as " + delivered.size()
				+ " separate messages, first: "
				+ (delivered.isEmpty() ? null : delivered.get(0).getMessageData()));
	}

	@Test
	public void testFrameWithinLimitIsDeliveredIntact() {
		List<Message> delivered = new ArrayList<>();
		NettyInboundHandler handler = new NettyInboundHandler(m -> {
			delivered.add(m);
			return null;
		}, null, LIMIT);
		EmbeddedChannel ch = new EmbeddedChannel(handler);

		byte[] body = new byte[LIMIT];
		body[0] = 0x31;
		ch.writeInbound(frame(body));

		assertEquals(1, delivered.size());
		assertEquals(Blob.wrap(body), delivered.get(0).getMessageData());
		assertTrue(ch.isOpen());
		ch.close();
	}
}
