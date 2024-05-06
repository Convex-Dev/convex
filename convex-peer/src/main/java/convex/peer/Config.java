package convex.peer;

import convex.core.data.Format;

/**
 * Static tools and utilities for Peer configuration
 */
public class Config {
	
	/**
	 * Default size for client receive ByteBuffers.
	 */
	public static final int RECEIVE_BUFFER_SIZE = Format.LIMIT_ENCODING_LENGTH*10+20;

	/**
	 * Size of default server socket receive buffer
	 */
	public static final int SOCKET_SERVER_BUFFER_SIZE = 16*65536;

	/**
	 * Size of default server socket buffers for an outbound peer connection
	 */
	public static final int SOCKET_PEER_BUFFER_SIZE = 16*65536;

	/**
	 * Size of default client socket receive buffer
	 */
	public static final int SOCKET_RECEIVE_BUFFER_SIZE = 65536;

	/**
	 * Size of default client socket send buffer
	 */
	public static final int SOCKET_SEND_BUFFER_SIZE = 2*65536;

	/**
	 * Delay before rebroadcasting Belief if not in consensus
	 */
	public static final long MAX_REBROADCAST_DELAY = 200;

	/**
	 * Timeout for syncing with an existing Peer
	 */
	public static final long PEER_SYNC_TIMEOUT = 60000;
	
	/**
	 * Number of milliseconds average time to drop low-staked Peers
	 */
	public static final double PEER_CONNECTION_DROP_TIME = 20000;

	/**
	 * Default number of outgoing connections for a Peer
	 */
	public static final Integer DEFAULT_OUTGOING_CONNECTION_COUNT = 10;


	/**
	 * Number of fields in a Peer STATUS message
	 */
	public static final long STATUS_COUNT = 9;

	/**
	 * Default size for incoming client transaction queue
	 * Note: this limits TPS for client transactions, will send failures if overloaded
	 */
	public static final int TRANSACTION_QUEUE_SIZE = 10000;

	/**
	 * Default size for incoming client query queue
	 * Note: this limits TPS for client queries, will send failures if overloaded
	 */
	public static final int QUERY_QUEUE_SIZE = 10000;
	
	/**
	 * Default timeout in milliseconds for client transactions
	 */
	public static final long DEFAULT_CLIENT_TIMEOUT = 8000;

	/**
	 * Size of incoming Belief queue
	 */
	public static final int BELIEF_QUEUE_SIZE = 200;

}
