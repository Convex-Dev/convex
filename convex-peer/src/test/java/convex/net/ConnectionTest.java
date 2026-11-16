package convex.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.message.Message;
import convex.net.impl.HandlerException;
import convex.net.impl.nio.Connection;

/**
 * Tests for the low level Connection class
 */
public class ConnectionTest {

	@Test
	public void testCloseExistingByteChannel() throws IOException {
		MemoryByteChannel channel=MemoryByteChannel.create(16);
		Connection connection=Connection.create(channel,message -> {},null);

		connection.close();
		connection.close();

		assertFalse(channel.isOpen());
		assertTrue(connection.isClosed());
	}

	/** The legacy NIO receiver enforces the same pre-allocation frame cap as Netty. */
	@Test
	public void testConfiguredReceiveLimit() throws Exception {
		MemoryByteChannel chan = MemoryByteChannel.create(16);
		MessageReceiver receiver = new MessageReceiver(message -> {}, 64);
		chan.write(ByteBuffer.wrap(new byte[] {65})); // one-byte VLQ declaring 65 body bytes

		assertThrows(BadFormatException.class, () -> receiver.receiveFromChannel(chan));
		assertEquals(64, receiver.getMaxMessageLength());
	}
	
	/**
	 * Messages sent through a channel far smaller than their total arrive whole and in
	 * order: the sender stalls each time the channel fills, and the receiver reassembles
	 * frames split across refills.
	 */
	@Test
	public void testMessageFlood() throws IOException, BadFormatException, HandlerException {
		ArrayList<Message> received=new ArrayList<>();
		MemoryByteChannel chan=MemoryByteChannel.create(100);
		MessageReceiver mr=new MessageReceiver(received::add);

		// Frames of 3 to 10 bytes end at varied points in the channel: 500 fill it about 30 times
		int NUM=500;
		int stalls=0;
		Connection conn=Connection.create(chan,null,null);
		try {
			for (int i=0; i<NUM; i++) {
				assertTrue(conn.sendData(floodValue(i).getEncoding()),"the previous frame is flushed in full");
				while (!conn.flushBytes()) {
					// The channel is full: drain it, then write the rest of the frame
					stalls++;
					assertTrue(mr.receiveFromChannel(chan)>0);
				}
			}
			while (mr.receiveFromChannel(chan)>0) {}
		} finally {
			conn.close();
		}

		assertTrue(stalls>=20,"the channel should fill many times, filled "+stalls);
		assertEquals(NUM,received.size());
		for (int i=0; i<NUM; i++) {
			assertEquals(floodValue(i),received.get(i).getPayload(null));
		}
	}

	/** Values of one to eight bytes, cycling */
	private static CVMLong floodValue(int i) {
		return CVMLong.create(1L<<(i%63));
	}

}
