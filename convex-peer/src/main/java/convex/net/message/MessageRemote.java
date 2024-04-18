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
	
	@Override
    public Connection getConnection() {
		return connection;
	}

	public MessageRemote withConnection(Connection peerConnection) {
		return new MessageRemote(peerConnection, type, payload, messageData);
	}
	

	
	@Override
	public boolean returnMessage(Message m) {
		try {
			Connection pc = getConnection();
			if ((pc == null) || pc.isClosed()) return false;
			return pc.sendMessage(m);
		} catch (IOException t) {
			// Ignore, probably IO error
			log.warn("Error returning message: {}",t.getMessage());
			return false;
		} 
	}
	
	/**
	 * Gets a String identifying the origin of the message. Used for logging.
	 * @return String representing message origin
	 */
	public String getOriginString() {
		Connection pc = getConnection();
		if (pc==null) return "Disconnected message";
		return pc.getRemoteAddress().toString();
	}


	@Override
	public void closeConnection() {
		Connection pc=getConnection();
		if (pc!=null) {
			pc.close();
		}
	}



}
