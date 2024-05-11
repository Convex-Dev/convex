package convex.net.message;

import java.io.IOException;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.net.Connection;
import convex.net.MessageType;

/**
 * Message subclass handling return of results via a remote connection
 */
public class MessageRemote extends Message {
	
	private final Connection connection;


	protected MessageRemote(Connection conn, MessageType type, ACell payload, Blob data) {
		super(type, payload,data);
		this.connection=conn;
	}

	public MessageRemote withConnection(Connection peerConnection) {
		return new MessageRemote(peerConnection, type, payload, messageData);
	}
	
	@Override
	public boolean returnMessage(Message m) {
		try {
			if (connection.isClosed()) return false;
			return connection.sendMessage(m);
		} catch (IOException t) {
			// Ignore, probably IO error
			log.warn("Error returning message: {}",t.getMessage());
			return false;
		} 
	}

	@Override
	public void closeConnection() {
		connection.close();
	}



}
