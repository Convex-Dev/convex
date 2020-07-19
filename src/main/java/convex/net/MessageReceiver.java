package convex.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.function.Consumer;
import java.util.logging.Logger;

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
	public static final int RECEIVE_BUFFER_SIZE = Format.MAX_ENCODING_LENGTH * 2 + 20;

	private ByteBuffer buffer = ByteBuffer.allocateDirect(RECEIVE_BUFFER_SIZE);
	private final Consumer<Message> action;
	private final Connection peerConnection;

	private long receivedMessageCount = 0;

	private static final Logger log = Logger.getLogger(MessageReceiver.class.getName());

	public MessageReceiver(Consumer<Message> receiveAction, Connection pc) {
		this.action = receiveAction;
		this.peerConnection = pc;
	}

	public Consumer<Message> getAction() {
		return action;
	}

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
	 * @param chan
	 * @throws IOException
	 * @return The number of bytes read from the channel
	 * @throws BadFormatException
	 */
	public synchronized int receiveFromChannel(ReadableByteChannel chan) throws IOException, BadFormatException {
		int n = chan.read(buffer);
		if (n == 0) {
			return 0;
		}
		if (n < 0) throw new ClosedChannelException();
		// Log.debug("Bytes received: "+n);

		while (buffer.position() >= 2) { // ensure we have at least a message length to read
			// peek message length at start of buffer. May throw BFE.
			int len = Format.peekMessageLength(buffer);

			// compute length of message length field at start of message
			int lengthLength = (len < 64) ? 1 : 2;
			if (buffer.position() < lengthLength + len) {
				return n; // message not yet fully received
			}

			// Log.debug("Message received with length: "+len);
			buffer.flip();

			// position buffer ready to receive message content (i.e. skip length
			// field). We still want to include the message code.
			buffer.position(lengthLength);

			// receive message, expecting the specified final position
			int expectedPosition = lengthLength + len;
			receiveMessage(buffer, expectedPosition);

			// keep remaining data in buffer, flip and prepare for more reads
			// there might be more messages / fragments on the channel
			buffer.compact();
		}
		return n;
	}

	/**
	 * Reads exactly one message from the ByteBuffer, checking that the position is
	 * advanced as expected.
	 * 
	 * Expects a message code at the buffer's current position.
	 * 
	 * Calls the receive action with the message if successfully received. Should be called with
	 * the correct store for this Connection.
	 * 
	 * @throws BadFormatException if the message is incorrectly formatted`
	 */
	private void receiveMessage(ByteBuffer bb, int expectedPosition) throws BadFormatException {
		int firstPos = bb.position();
		byte messageCode = bb.get();
		MessageType type = MessageType.decode(messageCode);
		Object payload = Format.read(bb);
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
				log.finest("Message received: " + message.getType());
				action.accept(message);
			} catch (Exception e) {
				log.warning("Exception not handled from: " + peerConnection.getRemoteAddress());
				e.printStackTrace();
			}
		} else {
			log.warning("Ignored message because no receive action set: " + message);
		}
	}

}
