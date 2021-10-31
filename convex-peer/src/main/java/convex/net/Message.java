package convex.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Belief;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;

/**
 * <p>Class representing a message to / from a specific PeerConnection</p>
 *
 * <p>This class is an immutable data structure, but NOT a representable on-chain
 * data structure, as it is part of the peer protocol layer.</p>
 *
 * <p>Messages may contain a Payload, which can be any Data Object.</p>
 */
public class Message {
	
	static final Logger log = LoggerFactory.getLogger(Message.class.getName());

	private final Connection connection;
	private final ACell payload;
	private final MessageType type;

	private Message(Connection peerConnection, MessageType type, ACell payload) {
		this.connection = peerConnection;
		this.type = type;
		this.payload = payload;
	}

	public static Message create(Connection peerConnection, MessageType type, ACell payload) {
		return new Message(peerConnection, type, payload);
	}

	public static Message create(Connection peerConnection, ACell o) {
		return create(peerConnection, MessageType.DATA, o);
	}

	public static Message createData(ACell o) {
		return create(null,MessageType.DATA,o);
	}

	public static Message createBelief(SignedData<Belief> sb) {
		return create(null,MessageType.BELIEF,sb);
	}

	public static Message createChallenge(SignedData<ACell> challenge) {
		return create(null,MessageType.CHALLENGE, challenge);
	}

	public static Message createResponse(SignedData<ACell> response) {
		return create(null,MessageType.RESPONSE, response);
	}

	public static Message createGoodBye(SignedData<ACell> peerKey) {
		return create(null,MessageType.GOODBYE, peerKey);
	}

	/**
	 * Gets the Connection instance associated with this Message
	 * @return Connection instance. May be null.
	 */
    public Connection getConnection() {
		return connection;
	}

	public Message withConnection(Connection peerConnection) {
		return new Message(peerConnection, type, payload);
	}

	@SuppressWarnings("unchecked")
	public <T extends ACell> T getPayload() {
		return (T) payload;
	}

	public MessageType getType() {
		return type;
	}

	public ACell getErrorCode() {
		ACell et=((AVector<?>)payload).get(2);
		return et;
	}

	@Override
	public String toString() {
		// TODO. Are tags really needed in `.toString`?
		return "#message {:type " + getType() + " :payload " + Utils.print(payload) + "}";
	}

	/**
	 * Gets the message ID for correlation, assuming this message type supports IDs.
	 *
	 * @return Message ID, or null if the message type does not use message IDs
	 */
	public CVMLong getID() {
		switch (type) {
			// Query and transact use a vector [ID ...]
			case QUERY:
			case TRANSACT: return (CVMLong) ((AVector<?>)payload).get(0);

			// Result is a special record type
			case RESULT: return (CVMLong)((Result)payload).getID();

			// Status ID is the single value
			case STATUS: return (CVMLong)(payload);

			default: return null;
		}
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




}
