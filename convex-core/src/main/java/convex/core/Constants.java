package convex.core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;

/**
 * Static class for global configuration constants that affect protocol
 * behaviour
 */
public class Constants {

	/**
	 * Limit of scheduled transactions run in a single Block
	 */
	public static final long MAX_SCHEDULED_TRANSACTIONS_PER_BLOCK = 100;

	/**
	 * Threshold of stake required to propose consensus
	 */
	public static final double PROPOSAL_THRESHOLD = 0.67;

	/**
	 * Threshold of stake required to confirm consensus
	 */
	public static final double CONSENSUS_THRESHOLD = 0.67;

	/**
	 * Initial timestamp for new States
	 */
	public static final long INITIAL_TIMESTAMP = Instant.parse("2020-02-02T00:20:20.0202Z").toEpochMilli();

	/**
	 * Juice price in the initial Genesis State
	 */
	public static final long INITIAL_JUICE_PRICE = 2L;

	/**
	 * Initial memory Pool of 1gb
	 */
	public static final long INITIAL_MEMORY_POOL = 1000000000L;

	/**
	 * Initial memory price per byte
	 */
	public static final long INITIAL_MEMORY_PRICE = 1000L;

	/**
	 * Max juice allowable for execution of a single transaction.
	 */
	public static final long MAX_TRANSACTION_JUICE = 1000000;

	/**
	 * Constant to set deletion of Etch temporary files on exit. Probably should be true, unless you want to dubug temp files.
	 */
	public static final boolean ETCH_DELETE_TEMP_ON_EXIT = true;

	/**
	 * Sequence number used for any new account
	 */
	public static final long INITIAL_SEQUENCE = 0;

	/**
	 * Size in bytes of constant overhead applied per non-embedded Cell in memory accounting
	 */
	public static final long MEMORY_OVERHEAD = 64;

	/**
	 * Default timeout in milliseconds for client transactions
	 */
	public static final long DEFAULT_CLIENT_TIMEOUT = 10000;

	/**
	 * Allowance for initial user / peer accounts
	 */
	public static final long INITIAL_ACCOUNT_ALLOWANCE = 10000000;

	/**
	 * Maximum supply of Convex Coins set at protocol level
	 */
	public static final long MAX_SUPPLY = Coin.SUPPLY;

	/**
	 * Maximum CVM execution depth
	 */
	public static final int MAX_DEPTH = 256;

	/**
	 * Initial global values for a new State
	 */
	public static final AVector<ACell> INITIAL_GLOBALS = Vectors.of(
			Constants.INITIAL_TIMESTAMP, 0L, Constants.INITIAL_JUICE_PRICE,Constants.INITIAL_MEMORY_POOL,Constants.INITIAL_MEMORY_POOL*Constants.INITIAL_MEMORY_PRICE);

	/**
	 * Maximum length of a symbolic name in bytes (keywords and symbols)
	 *
	 * Note: Chosen so that small qualified symbolic values are always embedded
	 */
	public static final int MAX_NAME_LENGTH = 128;

	/**
	 * Value used to indicate inclusion of a key in a Set. Must be a singleton instance
	 */
	public static final CVMBool SET_INCLUDED = CVMBool.TRUE;

	/**
	 * Value used to indicate exclusion of a key from a Set. Must be a singleton instance
	 */
	public static final CVMBool SET_EXCLUDED = CVMBool.FALSE;

	/**
	 * Length for public keys
	 */
	public static final int KEY_LENGTH = 32;

	/**
	 * Length for Hash values
	 */
	public static final int HASH_LENGTH = 32;

	/**
	 * Default number of outgoing connections for a Peer
	 */
	public static final Integer DEFAULT_OUTGOING_CONNECTION_COUNT = 20;

	/**
	 * Number of milliseconds average time to drop low-staked Peers
	 */
	public static final double PEER_CONNECTION_DROP_TIME = 20000;

	/**
	 * Minimum stake for a Peer to be considered by other Peers in consensus
	 */
	public static final long MINIMUM_EFFECTIVE_STAKE = Coin.GOLD*1;

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
	 * Number of fields in a Peer STATUS message
	 */
	public static final long STATUS_COUNT = 8;

	/**
	 * Default port for Convex Peers
	 */
	public static final int DEFAULT_PEER_PORT = 18888;

	/**
	 * Option for static compilation support. Set to true for static inlines on core
	 */
	// TODO: Should ultimately be true for production usage
	public static final boolean OPT_STATIC = false;

	/**
	 * Char to represent bad Unicode characters in printing
	 */
	public static final char BAD_CHARACTER = '\uFFFD';
	public static final byte[] BAD_CHARACTER_BYTES = new byte[] {(byte) 0xff, (byte) 0xfd };
	public static final String BAD_CHARACTER_STRING = new String(BAD_CHARACTER_BYTES, StandardCharsets.UTF_8);
	public static final byte[] BAD_CHARACTER_UTF = BAD_CHARACTER_STRING.getBytes(StandardCharsets.UTF_8);

	/**
	 * Default print limit
	 */
	public static final long PRINT_LIMIT = 4096;

	public static final AString PRINT_EXCEEDED_MESSAGE = Strings.create("<<Print limit exceeded>>");

	/**
	 * Default size for incoming client transaction queue
	 * Note: this limits TPS for client transactions, will send failures if overloaded
	 */
	public static final int TRANSACTION_QUEUE_SIZE = 1000;

	public static final int QUERY_QUEUE_SIZE = 1000;

	/**
	 * Size of incoming Belief queue
	 */
	public static final int BELIEF_QUEUE_SIZE = 500;

	/**
	 * Minimum milliseconds to retain a proposal before switching
	 */
	public static final long KEEP_PROPOSAL_TIME = 1000;
}
