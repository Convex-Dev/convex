package convex.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;
import convex.net.message.Message;

/**
 * Class responsible for buffered accumulation of data received over a connection.
 *
 * ByteBuffers received must be passed in via @receiveFromChannel
 *
 * Passes any successfully received objects to a specified Consumer, using the same thread on which the
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
	// Receive buffer must be big enough at least for one max sized message plus message header
	public static final int RECEIVE_BUFFER_SIZE = Constants.RECEIVE_BUFFER_SIZE;

	/**
	 * Buffer for receiving partial messages. Maintained ready for writing.
	 * 
	 * Maybe use a direct buffer since we are copying from the socket channel? But probably doesn't make any difference.
	 */
	private ByteBuffer buffer = ByteBuffer.allocate(RECEIVE_BUFFER_SIZE);

	private final Consumer<Message> action;
	private Consumer<Message> hook=null;
	private final Connection connection;

	private long receivedMessageCount = 0;

	private static final Logger log = LoggerFactory.getLogger(MessageReceiver.class.getName());

	public MessageReceiver(Consumer<Message> receiveAction, Connection pc) {
		this.action = receiveAction;
		this.connection = pc;
	}

	public Consumer<Message> getReceiceAction() {
		return action;
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
	 * This hopefully
	 * creates sufficient backpressure on clients sending a lot of messages.
	 *
	 * @param chan Byte channel
	 * @throws IOException If IO error occurs
	 * @return The number of bytes read from the channel
	 * @throws BadFormatException If a bad encoding is received
	 */
	public synchronized int receiveFromChannel(ReadableByteChannel chan) throws IOException, BadFormatException {
		int numRead=0;

		// first read a message length
		if (buffer.position()<2) {
			buffer.limit(2);
			numRead = chan.read(buffer);

			if (numRead < 0) {
				chan.close();
			    throw new ClosedChannelException();
			}

			// exit if we don't have at least 2 bytes for message length (may also be a message code)
			if (buffer.position()<2) return numRead;
		}

		// peek message length at start of buffer. May throw BFE.
		int len = Format.peekMessageLength(buffer);
		if (len<0) return numRead; // Not enough bytes for a message length yet
		
		// limit buffer to total message frame size including length
		int lengthLength = Format.getVLCLength(len);
		int totalFrameSize=lengthLength + len;
		buffer.limit(totalFrameSize);

		// try to read more bytes up to limit of total message size
		{
			int n=chan.read(buffer);
			if (n < 0) throw new ClosedChannelException();
			numRead+=n;
		}

		// exit if we are still waiting for more bytes to complete message
		if (buffer.hasRemaining()) return numRead;

		// Log.debug("Message received with length: "+len);
		buffer.flip(); // prepare for read

		// position buffer ready to receive message content (i.e. skip length
		// field). We still want to include the message code.
		buffer.position(lengthLength);
		byte mType=buffer.get();
		MessageType type=MessageType.decode(mType);
		
		byte[] bs=new byte[len-1]; // message data length after type byte
		buffer.get(bs);
		assert(!buffer.hasRemaining()); // should consume entire buffer!
		Blob messageData=Blob.wrap(bs);

		receiveMessage(type, messageData);

		// clear buffer
		buffer.clear();
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
	 * SECURITY: Gets called on NIO server thread
	 *
	 * @throws BadFormatException if the message is incorrectly formatted`
	 */
	private void receiveMessage(MessageType type, Blob encoding) throws BadFormatException {
		Message message = Message.create(connection, type, null, encoding);
		
		// call the receiver hook, if registered
		maybeCallHook(message);
		
		// Otherwise, send to the message receive action
		receivedMessageCount++;
		if (action != null) {
			try {
				log.trace("Message received: {}", message.getType());
				action.accept(message);
			} catch (Throwable e) {
				log.warn("Exception in receive action from: " + connection.getRemoteAddress(),e);
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
