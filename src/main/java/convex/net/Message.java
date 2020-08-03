package convex.net;

import convex.core.Belief;
import convex.core.data.AVector;
import convex.core.data.SignedData;
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

	private final Connection peerConnection;
	private final Object payload;
	private final MessageType type;

	private Message(Connection peerConnection, MessageType type, Object payload) {
		this.peerConnection = peerConnection;
		this.type = type;
		this.payload = payload;
	}

	public static Message create(Connection peerConnection, MessageType type, Object payload) {
		return new Message(peerConnection, type, payload);
	}

	public static Message create(Connection peerConnection, Object o) {
		return create(peerConnection, MessageType.DATA, o);
	}
	
	public static Message createData(Object o) {
		return create(null,MessageType.DATA,o);
	}
	
	public static Message createBelief(SignedData<Belief> sb) {
		return create(null,MessageType.BELIEF,sb);
	}

	public Connection getPeerConnection() {
		return peerConnection;
	}

	public Message withConnection(Connection peerConnection) {
		return new Message(peerConnection, type, payload);
	}

	@SuppressWarnings("unchecked")
	public <T> T getPayload() {
		return (T) payload;
	}

	public MessageType getType() {
		return type;
	}
	
	public Object getErrorCode() {
		Object et=((AVector<?>)payload).get(2);
		return et;
	}

	@Override
	public String toString() {
		return "#message {:type " + getType() + " :value " + Utils.ednString(payload) + "}";
	}

	/**
	 * Gets the message ID for correlation, assuming this message type supports IDs.
	 * 
	 * @return Message ID, or null if the message type does not use message IDs
	 */
	public Long getID() {
		switch (type) {
			case QUERY: return (Long) ((AVector<?>)payload).get(0);
			case RESULT: return (Long) ((AVector<?>)payload).get(0);
			case TRANSACT: return (Long) ((AVector<?>)payload).get(0);
			default: return null;
		}
	}




}
