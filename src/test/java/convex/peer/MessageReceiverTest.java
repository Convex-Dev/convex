package convex.peer;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;

import org.junit.Test;

import convex.core.data.ACell;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.store.Stores;
import convex.core.util.MemoryByteChannel;
import convex.net.Message;
import convex.net.MessageReceiver;
import convex.net.MessageType;
import convex.net.Connection;

public class MessageReceiverTest {

	@Test
	public void testSimpleMessages() throws IOException, BadFormatException {
		final ArrayList<Message> received = new ArrayList<>();

		// create a custom PeerConnection and MessageReceiver for testing
		// null Queue OK, we aren't queueing with our custom receive action
		MessageReceiver mr = new MessageReceiver(a -> received.add(a), null);
		MemoryByteChannel chan = MemoryByteChannel.create(10000);
		Connection pc = Connection.create(chan, null,Stores.current());

		ACell msg1 = RT.cvm("Hello World!");
		assertTrue(pc.sendData(msg1));
		ACell msg2 = RT.cvm(13L);
		assertTrue(pc.sendData(msg2));

		// need to call sendBytes to flush send buffer to channel
		// since we aren't using a Selector / SocketChannel here
		assertFalse(pc.sendBytes());

		mr.receiveFromChannel(chan);

		assertEquals(2, received.size());
		assertEquals(msg1, received.get(0).getPayload());
		assertEquals(msg2, received.get(1).getPayload());

		Message m1 = received.get(0);
		assertEquals(MessageType.DATA, m1.getType());
	}
}
