package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Blobs;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.store.Stores;
import convex.net.Connection;
import convex.net.MemoryByteChannel;
import convex.net.Message;
import convex.net.MessageReceiver;
import convex.net.MessageType;
import convex.net.impl.HandlerException;

public class MessageReceiverTest {

	@Test
	public void testSimpleMessages() throws IOException, BadFormatException, HandlerException {
		final ArrayList<Message> received = new ArrayList<>();

		MemoryByteChannel chan = MemoryByteChannel.create(10000);
		Connection pc = Connection.create(chan, null, Stores.current(), null);

		// create a custom PeerConnection and MessageReceiver for testing
		// null Queue OK, we aren't queueing with our custom receive action
		MessageReceiver mr = new MessageReceiver(a -> received.add(a), pc);

		ACell msg1 = RT.cvm("Hello World!");
		assertTrue(pc.sendMessage(Message.createDataResponse(CVMLong.ZERO,msg1)));
		ACell msg2 = RT.cvm(13L);
		assertTrue(pc.sendMessage(Message.createDataResponse(CVMLong.ZERO,msg2)));

		// need to call sendBytes to flush send buffer to channel
		// since we aren't using a Selector / SocketChannel here
		assertTrue(pc.flushBytes());

		// receive messages
		mr.receiveFromChannel(chan);
		assertEquals(2, received.size());
		assertEquals(Vectors.of(0,msg1), received.get(0).getPayload());
		assertEquals(Vectors.of(0,msg2), received.get(1).getPayload());

		Message m1 = received.get(0);
		assertEquals(MessageType.DATA, m1.getType());
	}
	
	@Test
	public void testBigMessage() throws IOException, BadFormatException, HandlerException {
		final ArrayList<Message> received = new ArrayList<>();

		MemoryByteChannel chan = MemoryByteChannel.create(1000);
		Connection pc = Connection.create(chan, null, Stores.current(), null);

		// create a custom PeerConnection and MessageReceiver for testing
		// null Queue OK, we aren't queueing with our custom receive action
		MessageReceiver mr = new MessageReceiver(a -> received.add(a), pc);

		ABlob blob = Blobs.createRandom(new Random(), 100000).getCanonical();
		Result r=Result.create(CVMLong.ONE, blob);
		Message msg=Message.createResult(r);
		pc.sendMessage(msg);

		// receive message
		while (!pc.flushBytes()) {
			mr.receiveFromChannel(chan);
		}
		mr.receiveFromChannel(chan); // complete receiving if needed
		
		assertEquals(1,received.size());
		
		Message rec=received.get(0);
		assertEquals(MessageType.RESULT, rec.getType());
		
		ACell r2=rec.getPayload();
		assertEquals(r,r2);
	}
}
