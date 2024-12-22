package convex.core.message;

import convex.core.exceptions.BadFormatException;

public enum MessageType {

	/**
	 * A message that requests the remote endpoint to respond with a signed
	 * response.
	 *
	 * The challenge must be signed to authenticate the challenger.
     *
     * The challenge is sent with a vector of [hash accountKey-of-the-challenged]
	 */
	CHALLENGE(1),

	/**
	 * A response to a challenge. The challengee must sign the response as proof of
	 * possession of the claimed address.
	 */
	RESPONSE(2),

	/**
	 * A message relaying data.
	 * 
	 * Payload is a Result:
	 * - Result
	 *
	 * Data is presented "as-is", and may be: 
	 * - the result of a missing data request
	 * - data sent ahead of another message requiring composite data
	 */
	DATA(3),

	/**
	 * A control command to a peer.
	 *
	 * Should only be accepted and acted upon when originating from trusted,
	 * authenticated senders.
	 */
	COMMAND(4),

	/**
	 * A request to provide data. Peers should not send this message unless
	 * both: a) they are unable to locate the given data in their local store b)
	 * They have reason to believe the targeted peer may be able to provide it
	 *
	 * Excessive invalid data requests may be considered a DoS attack by
	 * peers. Peers under load may ignore data requests.
	 *
	 * Payload is a Vector containing ID plus one or more hashes 
	 * i.e [:DR id hash1 hash2 ......]
	 * 
	 * Receiver should respond with a DATA message if the specified data is
	 * available in their store, and they are willing to fulfil the request
	 */
	DATA_REQUEST(5),

	/**
	 * A request to perform the specified query and return results.
	 *
	 * Payload is: [:Q id form address?]
	 *
	 * Receiver may may determine policies regarding whether to accept or reject
	 * queries, typically receiver will want to authenticate the sender and ensure
	 * good standing?
	 */
	QUERY(6),

	/**
	 * A message requesting a transaction be accepted by the receiving peer and
	 * included in the next available block.
	 *
	 * Payload is: [:TX id signed-data]
	 */
	TRANSACT(7),

	/**
	 * Message containing the Result for a corresponding COMMAND, QUERY or TRANSACT
	 * message.
	 *
	 * Payload is: Result
	 *
	 * Where:
	 * - Result is the result of the request, or the message if an error occurred
	 * - error-code is nil if the transaction succeeded, or error code if it failed
	 */
	RESULT(8),

	/**
	 * Communication of a latest Belief by a Peer.
	 *
	 * Payload is a Belief or a Signed Order, which is expected to be a Partial update
	 */
	BELIEF(9),
	
	/**
	 * Request the latest Belief from a Peer.
	 *
	 * Payload is:
	 * - nil for the latest Belief or Order
	 * - Long value for a specific Block in the Peer's Order.
	 */
	REQUEST_BELIEF(10),

	/**
	 * Communication of an intention to shutdown the connection. This is optional
	 * 
	 * Payload can be:
	 * - [:BYE] for generic close
	 * - [:BYE message] for close with a reason
	 */
	GOODBYE(11),

	/**
	 * Request for a peer status update.
	 * 
	 * Payload is the message ID as a Long.
	 *
	 * Expected Result is a Vector: [signed-belief-hash states-hash initial-state-hash peer-key consensus-state-hash]
	 */
	STATUS(12),
	
	/**
	 * Unknown message type
	 * 
	 * Payload could be anything
	 */
	UNKNOWN(13);

	private final byte messageCode;

	public byte getMessageCode() {
		return messageCode;
	}

	public static MessageType decode(int i) throws BadFormatException {
		switch (i) {
		case 1:
			return CHALLENGE;
		case 2:
			return RESPONSE;
		case 3:
			return DATA;
		case 4:
			return COMMAND;
		case 5:
			return DATA_REQUEST;
		case 6:
			return QUERY;
		case 7:
			return TRANSACT;
		case 8:
			return RESULT;
		case 9:
			return BELIEF;
		case 10:
			return REQUEST_BELIEF;
		case 11:
			return GOODBYE;
		case 12:
			return STATUS;
		case 13:
			return UNKNOWN;
		}
		throw new BadFormatException("Invalid message code: " + i);
	}

	MessageType(int i) {
		this.messageCode = (byte) i;
		if (i != messageCode) throw new Error("Message format byte out of range: " + i);
	}

}
