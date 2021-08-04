package convex.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;

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
	 * @param src Source ByteBuffer
	 * @return True if successfully buffered, false otherwise (insufficient send buffer
	 *         size)
	 */
	public boolean bufferMessage(ByteBuffer src) {
		synchronized (buffer) {
			buffer.compact();
			// return false if insufficient space to send
			if (buffer.remaining() < src.remaining()) {
				return false;
			}
			buffer.put(src);
			buffer.flip();
		}
		return true;
	}

	/**
	 * Try to send bytes on the outbound channel.
	 * 
	 * @return True if there are more bytes to send, false otherwise.
	 * @throws IOException If IO error occurs
	 */
	public boolean maybeSendBytes() throws IOException {
		synchronized (buffer) {
			if (!buffer.hasRemaining()) return false;

			// write to channel if possible. May write zero or more bytes
			channel.write(buffer);

			if (buffer.hasRemaining()) {
				log.debug("Send buffer full!");
				return true;
			} else {
				return false;
			}
		}
	}

}
