package convex.net.message;

import java.io.IOException;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.prim.CVMLong;
import convex.net.Connection;
import convex.net.MessageType;

/**
 * Message subclass handling return of results via a remote connection
 */
public class MessageRemote extends Message {
	
	private final Connection connection;


	protected MessageRemote(Connection conn, MessageType type, ACell payload) {
		super(type, payload,null);
		this.connection=conn;
	}
	
	@Override
    public Connection getConnection() {
		return connection;
	}

	public MessageRemote withConnection(Connection peerConnection) {
		return new MessageRemote(peerConnection, type, payload);
	}
	
	/**
	 * Reports a result back to the originator of the message.
	 * 
	 * Will set a Result ID if necessary.
	 * 
	 * @param res Result record
	 * @return True if reported successfully, false otherwise
	 */
	public boolean reportResult(Result res) {
		res=res.withID(getID());
		Connection pc = getConnection();
		if ((pc == null) || pc.isClosed()) return false;

		try {
			return pc.sendResult(res);
		} catch (Exception t) {
			// Ignore, probably IO error
			log.debug("Error reporting result: {}",t.getMessage());
			return false;
		}
	}

	public boolean reportResult(CVMLong id, ACell reply) {
		Connection pc = getConnection();
		if ((pc == null) || pc.isClosed()) return false;
		try {
			return pc.sendResult(id,reply);
		} catch (Exception t) {
			// Ignore, probably IO error
			log.debug("Error reporting result: {}",t.getMessage());
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
	public boolean sendData(ACell data) {
		Connection pc=getConnection();
		if (pc==null) return false;
		try {
			pc.sendData(data);
		} catch (IOException e) {
			return false;
		}
		return true;
	}

	@Override
	public boolean sendMissingData(Hash hash) {
		Connection pc=getConnection();
		if (pc==null) return false;
		try {
			pc.sendMissingData(hash);
		} catch (IOException e) {
			return false;
		}
		return true;
	}


}
