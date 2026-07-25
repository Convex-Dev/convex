package convex.restapi.api;

import convex.core.message.MessageType;

/**
 * Classification point for messages submitted through the generic HTTP endpoint.
 *
 * <p>The endpoint is an opt-in transport for the Peer protocol, so every message
 * class is currently permitted and the Peer dispatcher remains authoritative.
 * Keeping classification here makes any future HTTP-specific restriction explicit
 * and testable without duplicating protocol dispatch in the REST layer.</p>
 */
final class GenericMessagePolicy {

	enum MessageClass {
		CLIENT_REQUEST,
		PEER_PROTOCOL,
		RESPONSE,
		LATTICE,
		UNKNOWN
	}

	private GenericMessagePolicy() {}

	static MessageClass classify(MessageType type) {
		return switch (type) {
			case QUERY, TRANSACT, DATA_REQUEST -> MessageClass.CLIENT_REQUEST;
			case CHALLENGE, COMMAND, BELIEF, REQUEST_BELIEF, GOODBYE, STATUS, PING ->
					MessageClass.PEER_PROTOCOL;
			case DATA, RESULT -> MessageClass.RESPONSE;
			case LATTICE_VALUE, LATTICE_QUERY -> MessageClass.LATTICE;
			case UNKNOWN -> MessageClass.UNKNOWN;
		};
	}

	/**
	 * Returns whether HTTP adds an admission restriction for this message type.
	 * Currently permissive by design: the Peer dispatcher applies normal protocol
	 * handling and bounded workload queues after this classification point.
	 */
	static boolean allows(MessageType type) {
		classify(type); // Force exhaustive classification as MessageType evolves.
		return true;
	}
}
