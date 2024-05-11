package convex.net.message;

import java.util.function.Predicate;

import convex.core.data.ACell;
import convex.net.MessageType;

/**
 * Class representing a message to a local Server instance. This avoids going via a Connection
 */
public class MessageLocal extends Message {

	protected MessageLocal(MessageType type, ACell payload, Predicate<Message> handler) {
		super(type, payload,null,handler);
	}
	
	/**
	 * Create an instance with the given message data
	 * @param type Message type
	 * @param payload Message payload
	 * @param handler Handler for Results
	 * @return New MessageLocal instance
	 */
	public static MessageLocal create(MessageType type, ACell payload, Predicate<Message> handler) {
		return new MessageLocal(type,payload,handler);
	}

	@Override
	public void closeConnection() {
		// Nothing to close
	}
}
