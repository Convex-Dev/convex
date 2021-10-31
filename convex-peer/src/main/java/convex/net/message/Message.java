package convex.net.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Belief;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.net.Connection;
import convex.net.MessageType;

/**
 * <p>Class representing a message to / from a specific connection</p>
 * 
 * <p>Encapsulates both message content and a means of return communication</p>.
 *
 * <p>This class is an immutable data structure, but NOT a representable on-chain
 * data structure, as it is part of the peer protocol layer.</p>
 *
 * <p>Messages may contain a Payload, which can be any Data Object.</p>
 */
public abstract class Message {
	
	static final Logger log = LoggerFactory.getLogger(Message.class.getName());

	protected final ACell payload;
	protected final MessageType type;

	protected Message(MessageType type, ACell payload) {
		this.type = type;
		this.payload = payload;
	}

	public static MessageRemote create(Connection peerConnection, MessageType type, ACell payload) {
		return new MessageRemote(peerConnection, type, payload);
	}

	public static MessageRemote create(Connection peerConnection, ACell o) {
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
	public abstract boolean reportResult(Result res);

	/**
	 * Report a result for a given message ID
	 * @param id Message ID
	 * @param reply Value for result
	 * @return True if reported successfully, false otherwise
	 */
	public abstract boolean reportResult(CVMLong id, ACell reply);
	
	/**
	 * Gets a String identifying the origin of the message. Used for logging.
	 * @return String representing message origin
	 */
	public abstract String getOriginString();

	/**
	 * Sends a cell of data to the connected Peer
	 * @param data Data to send
	 * @return true if data sent, false otherwise
	 */
	public abstract boolean sendData(ACell data);

	/**
	 * Sends a missing data request to the connected Peer
	 * @param hash HAsh of missing data
	 * @return True if request sent, false otherwise
	 */
	public abstract boolean sendMissingData(Hash hash);




}
