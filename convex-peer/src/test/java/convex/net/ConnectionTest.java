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
import convex.core.util.Utils;
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
	
	@Test
	public void testMessageFlood() throws IOException, BadFormatException, InterruptedException, HandlerException {
		final ArrayList<Message> received = new ArrayList<>();

		MemoryByteChannel chan = MemoryByteChannel.create(100);
		Connection conn=Connection.create(chan, null, null);

		// create a custom PeerConnection and MessageReceiver for testing
		// null Queue OK, we aren't queueing with our custom receive action
		MessageReceiver mr = new MessageReceiver(a -> {
			synchronized (received) {
				received.add(a);
			}
		});
		
		Thread receiveThread=new Thread(()-> {
			while (!Thread.currentThread().isInterrupted()) {
				try {
					mr.receiveFromChannel(chan);
					if(Thread.interrupted()) return;
				} catch (BadFormatException | IOException | HandlerException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					throw Utils.sneakyThrow(e);
				} 
			}
		});
		receiveThread.start();
		
		int NUM=10000;
		int sentCount = 0;
		int resendCount = 0;
		
		for (int i=0; i<NUM; i++) {
			boolean sent=false;
			CVMLong value=CVMLong.create(i);
			while(!sent) {
				sent=conn.sendData(value.getEncoding());
				if (sent) {
					sentCount++;
				} else {
					resendCount++;
				}
				
				boolean flushed=false;
				while (!flushed) {
					flushed=conn.flushBytes();
				}
			}
		}
		assertEquals(NUM,sentCount);
			
		// read everything still left in the channel before continuing
		int rec=-1;
		while (rec!=0) {
			rec=mr.receiveFromChannel(chan);
		}
		
		if (received.size()<NUM) {
			System.out.println("Missing messages? Had to resend: "+resendCount);
		}
		
		assertEquals(NUM,received.size());
		
		receiveThread.interrupt();
		receiveThread.join();
	}

}
