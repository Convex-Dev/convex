package convex.net.message;

import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.net.MessageType;

/**
 * Class representing a message to a local Server instance. This avoids going via a Connection
 */
public class MessageLocal extends Message {

	protected Consumer<Message> returnHandler;
	
	protected MessageLocal(MessageType type, ACell payload, Consumer<Message> handler) {
		super(type, payload,null);
		this.returnHandler=handler;
	}
	
	/**
	 * Create an instance with the given message data
	 * @param type Message type
	 * @param payload Message payload
	 * @param handler Handler for Results
	 * @return New MessageLocal instance
	 */
	public static MessageLocal create(MessageType type, ACell payload, Consumer<Message> handler) {
		return new MessageLocal(type,payload,handler);
	}
	
	@Override
	public boolean returnMessage(Message m) {
		returnHandler.accept(m);
		return true;
	}

	@Override
	public void closeConnection() {
		// Nothing to close
	}
}
