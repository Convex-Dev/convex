package convex.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.data.ACell;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;

/**
 * Class responsible for buffered accumulation of messages received by a Peer.
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

	// Maybe use a direct buffer since we are copying from the socket channel?
	// But probably doesn't make any difference.
	private ByteBuffer buffer = ByteBuffer.allocate(RECEIVE_BUFFER_SIZE);

	private final Consumer<Message> action;
	private final Connection peerConnection;

	private long receivedMessageCount = 0;

	private static final Logger log = LoggerFactory.getLogger(MessageReceiver.class.getName());

	public MessageReceiver(Consumer<Message> receiveAction, Connection pc) {
		this.action = receiveAction;
		this.peerConnection = pc;
	}

	public Consumer<Message> getAction() {
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

			if (numRead < 0) throw new ClosedChannelException();

			// exit if we don't have at least 2 bytes for message length
			if (buffer.position()<2) return numRead;
		}

		// peek message length at start of buffer. May throw BFE.
		int len = Format.peekMessageLength(buffer);
		int lengthLength = (len < 64) ? 1 : 2;

		// limit buffer to total message size including length
		int size=lengthLength + len;
		buffer.limit(size);

		// try to read more bytes up to limit of total message size
		{
			int n=chan.read(buffer);
			if (n < 0) throw new ClosedChannelException();
			numRead+=n;
		}

		// exit if we are still waiting for more bytes
		if (buffer.hasRemaining()) return numRead;

		// Log.debug("Message received with length: "+len);
		buffer.flip();

		// position buffer ready to receive message content (i.e. skip length
		// field). We still want to include the message code.
		buffer.position(lengthLength);

		// receive message, expecting the specified final position
		int expectedPosition = lengthLength + len;
		receiveMessage(buffer, expectedPosition);

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
	private void receiveMessage(ByteBuffer bb, int expectedPosition) throws BadFormatException {
		int firstPos = bb.position();
		byte messageCode = bb.get();
		MessageType type = MessageType.decode(messageCode);

		// Read an object from message
		ACell payload = Format.read(bb);

		int pos = bb.position();
		if (pos != expectedPosition) {
			String m = "Unexpected message length, expected: " + (expectedPosition - firstPos) + " but got:"
					+ (pos - firstPos);
			log.info(m);
			throw new BadFormatException(m);
		}
		Message message = Message.create(peerConnection, type, payload);
		receivedMessageCount++;
		if (action != null) {
			try {
				log.trace("Message received: {}", message.getType());
				action.accept(message);
			} catch (Exception e) {
				log.warn("Exception not handled from: " + peerConnection.getRemoteAddress());
				e.printStackTrace();
			}
		} else {
			log.warn("Ignored message because no receive action set: " + message);
		}
	}

}
