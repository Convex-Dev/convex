package convex.net.message;

import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.store.AStore;
import convex.net.Connection;
import convex.net.MessageType;
import convex.peer.Server;

/**
 * Class representing a message to a local Server instance. This avoids going via a Connection
 */
public class MessageLocal extends Message {

	protected Server server;
	protected AStore store;
	protected Consumer<Message> returnHandler;
	
	protected MessageLocal(MessageType type, ACell payload, Server server, Consumer<Message> handler) {
		super(type, payload,null);
		this.server=server;
		this.returnHandler=handler;
	}
	
	/**
	 * Create an instance with the given message data
	 * @param type Message type
	 * @param payload Message payload
	 * @param server Local server instance
	 * @param handler Handler for Results
	 * @return New MessageLocal instance
	 */
	public static MessageLocal create(MessageType type, ACell payload, Server server, Consumer<Message> handler) {
		return new MessageLocal(type,payload,server,handler);
	}
	
	@Override
	public boolean returnMessage(Message m) {
		if (m.getID()==null) {
			throw new IllegalArgumentException("Return message must have correlation ID");
		}
		returnHandler.accept(m);
		return true;
	}

	@Override
	public String getOriginString() {
		return "Local Peer";
	}

	@Override
	public Connection getConnection() {
		return null;
	}

	@Override
	public void closeConnection() {
		// Nothing to close
	}



}
