package convex.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;
import convex.net.impl.HandlerException;

/**
 * Class responsible for buffered accumulation of data received over a single connection.
 *
 * Data received must be passed in via @receiveFromChannel
 *
 * Passes any successfully received Messages to a specified Consumer, using the same thread on which the
 * MessageReceiver was called.
 *
 * <blockquote>
 *   <p>"There are only two hard problems in distributed systems: 2. Exactly-once
 *   delivery 1. Guaranteed order of messages 2. Exactly-once delivery"
 *   </p>
 *   <footer>- attributed to Mathias Verraes</footer>
 * </blockquote>
 *
 *
 */
public class MessageReceiver {
	// Initial size of buffers allocated. Enough for small messages, bigger ones will get re-allocated
	public static final int INITIAL_RECEIVE_BUFFER_SIZE = 512;

	/**
	 * Buffer for receiving partial messages. Maintained ready for writing.
	 * 
	 * Maybe use a direct buffer since we are copying from the socket channel? But probably doesn't make any difference.
	 */
	private ByteBuffer buffer = ByteBuffer.allocate(INITIAL_RECEIVE_BUFFER_SIZE);
;

	private final Consumer<Message> action;
	private Consumer<Message> hook=null;
	private final Connection connection;

	private long receivedMessageCount = 0;

	private static final Logger log = LoggerFactory.getLogger(MessageReceiver.class.getName());

	public MessageReceiver(Consumer<Message> receiveAction, Connection pc) {
		this.action = receiveAction;
		this.connection = pc;
	}
	
	/**
	 * Get the number of messages received in total by this Receiver
	 * @return Count of messages received
	 */
	public long getReceivedCount() {
		return receivedMessageCount;
	}

	/**
	 * Handles receipt of bytes from a channel. Should be called with a
	 * ReadableByteChannel containing bytes received.
	 *
	 * May be called multiple times during receipt of a single message, i.e. can
	 * handle partial message receipt.
	 *
	 * Will consume enough bytes from channel to handle exactly one message. Bytes
	 * will be left unconsumed on the channel if more are available.
	 *
	 * This hopefully creates sufficient backpressure on clients sending a lot of messages.
	 *
	 * @param chan Byte channel
	 * @return The number of bytes read from the channel, or -1 if EOS
	 * @throws IOException If IO error occurs
	 * @throws BadFormatException If a bad encoding is received
	 * @throws HandlerException If the message handler throws an unexpected Exception
 	 */
	public synchronized int receiveFromChannel(ReadableByteChannel chan) throws BadFormatException, HandlerException, IOException {
		int numRead=0;

		numRead = chan.read(buffer);

		if (numRead <= 0) {
			// no bytes received / at end of stream
			return numRead;
		}

		while (buffer.position()>0) {
			// peek message length at start of buffer. May throw BFE.
			int len = Format.peekMessageLength(buffer);
			if (len<0) return numRead; // Not enough bytes for a message length yet
			
			int lengthLength = Format.getVLQCountLength(len);
			int totalFrameSize=lengthLength + len;
			
			if (totalFrameSize>buffer.capacity()) {
				int newSize=Math.max(totalFrameSize, buffer.position());
				ByteBuffer newBuffer=ByteBuffer.allocate(newSize);
				buffer.flip();
				newBuffer.put(buffer);
				buffer=newBuffer;
			}
			
			// Exit if we hven't got the full message yet
			if (buffer.position()<totalFrameSize) return numRead;
	
			// At this point we know we have a full message. Wrap it as a Blob ready to receive message
			// From this point onwards MUST NOT mutate buffer backing array
			Blob messageData=Blob.wrap(buffer.array(),lengthLength,len);
	
			// check if we have more bytes
			int receivedLimit=buffer.position();
			if (receivedLimit>totalFrameSize) {
				// If more bytes, need to copy into new buffer
				int newSize=Math.max(INITIAL_RECEIVE_BUFFER_SIZE, receivedLimit-totalFrameSize);
				ByteBuffer newBuffer = ByteBuffer.allocate(newSize);
				buffer.position(totalFrameSize);
				buffer.limit(receivedLimit);
				newBuffer.put(buffer);
				buffer=newBuffer;
			} else {
				// No extra bytes, so simply allocate a fresh buffer for next usage
				buffer=ByteBuffer.allocate(INITIAL_RECEIVE_BUFFER_SIZE);
			}
			receiveMessage(messageData);
		}
		return numRead;
	}

	/**
	 * Reads exactly one message from the ByteBuffer, checking that the position is
	 * advanced as expected. Buffer must contain sufficient bytes for given message length.
	 *
	 * Expects a message code at the buffer's current position.
	 *
	 * Calls the receive action with the message if successfully received. Should be called with
	 * the correct store for this Connection.
	 *
	 * SECURITY: Gets called on NIO thread for Server / Client connections
	 *
	 * @throws BadFormatException if the message is incorrectly formatted
	 * @throws HandlerException If the message handler throws an unexpected Exception
	 */
	private void receiveMessage(Blob messageData) throws BadFormatException, HandlerException {
		if (messageData.count()<1) throw new BadFormatException("Empty message");
		
		byte mType=messageData.byteAtUnchecked(0);
		MessageType type=MessageType.decode(mType);

		Blob encoding=messageData.slice(1);
		Message message = Message.create(connection, type, encoding);
		
		// call the receiver hook, if registered
		maybeCallHook(message);
		
		// Otherwise, send to the message receive action
		receivedMessageCount++;
		if (action != null) {
			log.trace("Message received: {}", message.getType());
			try {
				action.accept(message);
			} catch (Exception e) {
				if (e instanceof InterruptedException) {
					// maintain interrupt status
					Thread.currentThread().interrupt();
				}
				throw new HandlerException("Error in message receive action handler: "+e.getMessage(),e);
			}
		} else {
			log.warn("Ignored message because no receive action set: " + message);
		}
	}

	private void maybeCallHook(Message message) {
		Consumer<Message> hook=this.hook;
		if (hook!=null) {
			hook.accept(message);
		}
	}

	/**
	 * Sets an optional additional message receiver hook (for debugging / observability purposes)
	 * @param hook Hook to call when a message is received
	 */
	public void setHook(Consumer<Message> hook) {
		this.hook = hook;
	}

}
