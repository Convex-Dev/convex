package convex.peer;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import org.junit.Test;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.store.Stores;
import convex.net.Connection;
import convex.net.MemoryByteChannel;
import convex.net.MessageReceiver;
import convex.net.MessageType;
import convex.net.message.Message;

public class MessageReceiverTest {

	@Test
	public void testSimpleMessages() throws IOException, BadFormatException {
		final ArrayList<Message> received = new ArrayList<>();

		MemoryByteChannel chan = MemoryByteChannel.create(10000);
		Connection pc = Connection.create(chan, null, Stores.current(), null);

		// create a custom PeerConnection and MessageReceiver for testing
		// null Queue OK, we aren't queueing with our custom receive action
		MessageReceiver mr = new MessageReceiver(a -> received.add(a), pc);

		ACell msg1 = RT.cvm("Hello World!");
		assertTrue(pc.sendData(msg1));
		ACell msg2 = RT.cvm(13L);
		assertTrue(pc.sendData(msg2));

		// need to call sendBytes to flush send buffer to channel
		// since we aren't using a Selector / SocketChannel here
		assertTrue(pc.flushBytes());

		// receive first message
		mr.receiveFromChannel(chan);
		assertEquals(1, received.size());
		assertEquals(msg1, received.get(0).getPayload());

		// receive second message
		mr.receiveFromChannel(chan);
		assertEquals(2, received.size());
		assertEquals(msg2, received.get(1).getPayload());

		Message m1 = received.get(0);
		assertEquals(MessageType.DATA, m1.getType());
	}
	
	@Test
	public void testBigMessage() throws IOException, BadFormatException {
		final ArrayList<Message> received = new ArrayList<>();

		MemoryByteChannel chan = MemoryByteChannel.create(1000);
		Connection pc = Connection.create(chan, null, Stores.current(), null);

		// create a custom PeerConnection and MessageReceiver for testing
		// null Queue OK, we aren't queueing with our custom receive action
		MessageReceiver mr = new MessageReceiver(a -> received.add(a), pc);

		Blob blob = Blob.createRandom(new Random(), 10000);
		Blob enc=Format.encodeMultiCell(blob);
		Message msg=Message.createMessage(pc, MessageType.DATA, enc);
		pc.sendMessage(msg);

		// need to call sendBytes to flush send buffer to channel
		// since we aren't using a Selector / SocketChannel here
		assertTrue(pc.flushBytes());

		// receive message
		while (!pc.flushBytes()) {
			mr.receiveFromChannel(chan);
		}
		mr.receiveFromChannel(chan); // complete receiving if needed
		
		assertEquals(1,received.size());
		
		Message rec=received.get(0);
		assertEquals(MessageType.DATA, rec.getType());
		
		Blob recData=rec.getMessageData();
		assertEquals(enc,recData);
		assertEquals(blob,Format.decodeMultiCell(recData));
	}
}
