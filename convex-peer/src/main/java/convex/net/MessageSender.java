package convex.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;

/**
 * Message sender responsible for moving bytes from a ByteBuffer to a ByteChannel
 * 
 * Must call maybeSendBytes to attempt to flush buffer to channel.
 */
public class MessageSender {
	public static final int SEND_BUFFER_SIZE = Constants.SEND_BUFFER_SIZE;

	private final ByteChannel channel;

	/**
	 * Buffer for send bytes. Retained in a state ready for reading, so we flip on
	 * initialisation. Must be accessed holding lock on buffer.
	 */
	private final ByteBuffer buffer = ByteBuffer.allocate(SEND_BUFFER_SIZE).flip();

	protected static final Logger log = LoggerFactory.getLogger(MessageSender.class.getName());

	public MessageSender(ByteChannel channel) {
		this.channel = channel;
	}

	/**
	 * Buffers a message for sending.
	 * 
	 * @param messageFrame Source ByteBuffer containing complete message bytes (including length)
	 * @return True if successfully buffered, false otherwise (insufficient send buffer
	 *         size)
	 */
	public boolean bufferMessage(ByteBuffer messageFrame) {
		synchronized (buffer) {
			// compact buffer, ready for writing			
			buffer.compact();
			
			// return false if insufficient space to send
			if (buffer.remaining() < messageFrame.remaining()) {
				// flip to maintain readiness for writing
				buffer.flip();
				return false;
			}
			buffer.put(messageFrame);
			// flip so ready for reading once again
			buffer.flip();
		}
		return true;
	}

	/**
	 * Try to send bytes on the outbound channel.
	 * 
	 * @return True if all bytes have been sent, false otherwise.
	 * @throws IOException If IO error occurs
	 */
	public boolean maybeSendBytes() throws IOException {
		synchronized (buffer) {
			if (!buffer.hasRemaining()) return true;

			// write to channel if possible. May write zero or more bytes
			channel.write(buffer);

			if (buffer.hasRemaining()) {
				log.debug("Send buffer full!");
				return false;
			} else {
				return true;
			}
		}
	}

}
