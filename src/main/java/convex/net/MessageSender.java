package convex.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.logging.Logger;

import convex.core.data.Format;

public class MessageSender {
	public static final int SEND_BUFFER_SIZE = Format.MAX_ENCODING_LENGTH * 10;

	private final ByteChannel channel;

	/**
	 * Buffer for send bytes. Retained in a state ready for reading, so we flip on
	 * initialisation. Must be accessed holding lock on buffer.
	 */
	private final ByteBuffer buffer = ByteBuffer.allocateDirect(SEND_BUFFER_SIZE).flip();

	protected static final Logger log = Logger.getLogger(MessageSender.class.getName());

	public MessageSender(ByteChannel channel) {
		this.channel = channel;
	}

	/**
	 * Buffers a message for sending.
	 * 
	 * @param src
	 * @return True if successfully buffered, false otherwise (insufficient buffer
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
	 * @throws IOException
	 */
	public boolean maybeSendBytes() throws IOException {
		synchronized (buffer) {
			if (!buffer.hasRemaining()) return false;

			// write to channel if possible. May write zero or more bytes
			channel.write(buffer);

			if (buffer.hasRemaining()) {
				log.warning("Send buffer full!");
				return true;
			} else {
				return false;
			}
		}
	}

}
