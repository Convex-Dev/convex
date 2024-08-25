package convex.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Message sender responsible for moving bytes from a ByteBuffer to a ByteChannel
 * 
 * Must call maybeSendBytes to attempt to flush buffer to channel.
 */
public class MessageSender {
	private final ByteChannel channel;

	/**
	 * Buffer for send bytes. Retained in a state ready for reading, so we flip on
	 * initialisation. Must be accessed holding lock on buffer.
	 */
	private ByteBuffer buffer = null;

	protected static final Logger log = LoggerFactory.getLogger(MessageSender.class.getName());

	public MessageSender(ByteChannel channel) {
		this.channel = channel;
	}

	/**
	 * Buffers a message for sending. Message buffer should be flipped, ready for reading
	 * 
	 * @param messageFrame Source ByteBuffer containing complete message bytes (including length)
	 * @return True if successfully buffered, false otherwise (insufficient send buffer
	 *         size)
	 * @throws IOException In case of IO Error
	 */
	public synchronized boolean bufferMessage(ByteBuffer messageFrame) throws IOException {
		if (buffer!=null) return false;
		buffer=messageFrame;
		
		// try to send bytes immediately
		maybeSendBytes();
		
		// Return true because the message is in flight / buffered successfully
		return true;
	}

	/**
	 * Try to send bytes on the outbound channel.
	 * 
	 * @return True if all bytes have been sent, false otherwise.
	 * @throws IOException If IO error occurs
	 */
	public synchronized boolean maybeSendBytes() throws IOException {
		if (buffer==null) return true;
		if (!buffer.hasRemaining()) {
			buffer=null;
			return true;
		}

		// write to channel if possible. May write zero or more bytes
		channel.write(buffer);

		if (buffer.hasRemaining()) {
			return false;
		} else {
			buffer=null;
			return true;
		}
	}

	/**
	 * Checks if this sender is ready to send a message. Caller should synchronise on 
	 * this sender in case the state changes concurrently
	 * 
	 * @return True if ready, false otherwise
	 */
	public boolean canSendMessage() {
		return buffer==null;
	}

}
