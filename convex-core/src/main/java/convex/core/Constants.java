package convex.core;

import java.time.Instant;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Format;
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
	public static final double PROPOSAL_THRESHOLD = 0.50;

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
	public static final long INITIAL_MEMORY_PRICE = 10L;

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
	public static final long DEFAULT_CLIENT_TIMEOUT = 6000;

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
			Constants.INITIAL_TIMESTAMP, 0L, Constants.INITIAL_JUICE_PRICE);

	/**
	 * Maximum length of a symbolic name in characters (keywords and symbols)
	 *
	 * Note: Chosen so that small qualified symbolic values are always embedded
	 */
	public static final int MAX_NAME_LENGTH = 64;

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
	 * Minimum stake for a PEer to be considered by other Peers in consensus
	 */
	public static final long MINIMUM_EFFECTIVE_STAKE = Coin.GOLD*1;

	/**
	 * Default size for client receive buffers.
	 */
	public static final int RECEIVE_BUFFER_SIZE = Format.LIMIT_ENCODING_LENGTH*10+20;

	/**
	 * Default size for client receive buffers.
	 */
	public static final int SEND_BUFFER_SIZE = Format.LIMIT_ENCODING_LENGTH*10+20;


	/**
	 * Size of default server socket receive buffer
	 */
	public static final int SOCKET_SERVER_BUFFER_SIZE = 16*65536;

	/**
	 * Size of default server socket buffers for a peer connection
	 */
	public static final int SOCKET_PEER_BUFFER_SIZE = 16*65536;

	/**
	 * Size of default client socket receive buffer
	 */
	public static final int SOCKET_RECEIVE_BUFFER_SIZE = 65536;

	/**
	 * Size of default client socket send buffer
	 */
	public static final int SOCKET_SEND_BUFFER_SIZE = 65536;

	/**
	 * Delay before rebroadcasting Belief if not in consensus
	 */
	public static final long MAX_REBROADCAST_DELAY = 100;

	/**
	 * Delay before a Peer produces another Block. 
	 * 
	 * Note: This may be the bottleneck in some benchmarks! Set to 0 if blocks are being delayed
	 */
	public static final long MIN_BLOCK_TIME = 0;

	/**
	 * Timeout for syncing with an existing Peer
	 */
	public static final long PEER_SYNC_TIMEOUT = 60000;

	/**
	 * Number of fields in a Peer STATUS message
	 */
	public static final long STATUS_COUNT = 5;

	/**
	 * Default port for Convex Peers
	 */
	public static final int DEFAULT_PEER_PORT = 18888;

	/**
	 * Option for static compilation support
	 */
	public static final boolean OPT_STATIC = false;

	/**
	 * Char to represent bad Unicode characters in printing
	 */
	public static final char BAD_CHARACTER = '?';
	public static final String BAD_CHARACTER_STRING = Character.toString(BAD_CHARACTER);
}
