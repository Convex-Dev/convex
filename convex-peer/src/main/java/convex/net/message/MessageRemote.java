package convex.net.message;

import java.io.IOException;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.prim.CVMLong;
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
			Message msg=Message.createResult(res);
			return pc.sendMessage(msg);
		} catch (IOException t) {
			// Ignore, probably IO error
			log.warn("Error reporting result: {}",t.getMessage());
			return false;
		}
	}

	public boolean reportResult(CVMLong id, ACell reply) {
		Connection pc = getConnection();
		if ((pc == null) || pc.isClosed()) return false;
		try {
			Message msg=Message.createResult(id,reply,null);
			return pc.sendMessage(msg);
		} catch (IOException t) {
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
			Blob enc=Format.encodedBlob(data);
			return pc.sendData(enc);
		} catch (IOException e) {
			return false;
		}
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
