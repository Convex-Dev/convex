package convex.core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;

/**
 * Static class for global configuration constants that affect protocol
 * behaviour
 */
public class Constants {

	/**
	 * Initial timestamp for new States
	 */
	public static final long INITIAL_TIMESTAMP = Instant.parse("2020-12-06T05:08:13.0864Z").toEpochMilli();
	// public static final long INITIAL_TIMESTAMP = Instant.parse("2024-12-06T05:08:13.0864Z").toEpochMilli();

	/**
	 * Juice price in the initial Genesis State
	 */
	public static final long INITIAL_JUICE_PRICE = 10L;

	/**
	 * Initial memory Pool of 1mb
	 */
	public static final long INITIAL_MEMORY_POOL = 1000000L;

	/**
	 * Initial memory price per byte 0.001 Convex Gold
	 */
	public static final long INITIAL_MEMORY_PRICE = 1000000L;

	/**
	 * Memory Pool of growth increment 1mb
	 */
	public static final long MEMORY_POOL_GROWTH = 1000000L;

	/**
	 * Memory Pool of growth interval (once per day). This means regular price drops
	 * in memory pool
	 */
	public static final long MEMORY_POOL_GROWTH_INTERVAL = 1000L * 24 * 3600;

	/**
	 * Max juice allowable during execution of a single transaction.
	 */
	public static final long MAX_TRANSACTION_JUICE = 10000000;

	/**
	 * Max transactions in a legal Block.
	 */
	public static final int MAX_TRANSACTIONS_PER_BLOCK = 1024;

	/**
	 * Constant to set deletion of Etch temporary files on exit. Probably should be
	 * true, unless you want to dubug temp files.
	 */
	public static final boolean ETCH_DELETE_TEMP_ON_EXIT = true;

	/**
	 * Sequence number used for any new account
	 */
	public static final long INITIAL_SEQUENCE = 0;

	/**
	 * Initial fees in global state
	 * 
	 */
	public static final long INITIAL_FEES = 0;

	/**
	 * Size in bytes of constant overhead applied per non-embedded Cell in memory
	 * accounting
	 */
	public static final long MEMORY_OVERHEAD = 64;

	/**
	 * Maximum supply of Convex Coins set at protocol level
	 */
	public static final long MAX_SUPPLY = Coin.MAX_SUPPLY;

	/**
	 * Maximum CVM execution depth
	 */
	public static final int MAX_DEPTH = 256;

	/**
	 * Initial global values for a new State
	 */
	public static final AVector<ACell> INITIAL_GLOBALS = Vectors.of(
			Constants.INITIAL_TIMESTAMP, Constants.INITIAL_FEES,
			Constants.INITIAL_JUICE_PRICE, Constants.INITIAL_MEMORY_POOL,
			Constants.INITIAL_MEMORY_POOL * Constants.INITIAL_MEMORY_PRICE);

	/**
	 * Maximum length of a symbolic name in bytes (keywords and symbols)
	 *
	 * Note: Chosen so that small qualified symbolic values are always embedded
	 */
	public static final int MAX_NAME_LENGTH = 128;

	/**
	 * Value used to indicate inclusion of a key in a Set. Must be a singleton
	 * instance
	 */
	public static final CVMBool SET_INCLUDED = CVMBool.TRUE;

	/**
	 * Value used to indicate exclusion of a key from a Set. Must be a singleton
	 * instance
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
	 * Option for static compilation support. Set to true for static inlines on core
	 */
	// TODO: Should ultimately be true for production usage
	public static final boolean OPT_STATIC = true;

	/**
	 * Char to represent bad Unicode characters in printing
	 */
	public static final char BAD_CHARACTER = '\uFFFD';
	public static final byte[] BAD_CHARACTER_BYTES = new byte[] { (byte) 0xff, (byte) 0xfd };
	public static final String BAD_CHARACTER_STRING = new String(BAD_CHARACTER_BYTES, StandardCharsets.UTF_8);
	public static final byte[] BAD_CHARACTER_UTF = BAD_CHARACTER_STRING.getBytes(StandardCharsets.UTF_8);

	/**
	 * Default print limit
	 */
	public static final long PRINT_LIMIT = 65536;

	public static final String PRINT_EXCEEDED_STRING = "<<Print limit exceeded>>";
	public static final AString PRINT_EXCEEDED_MESSAGE = Strings.create(PRINT_EXCEEDED_STRING);

	/**
	 * Default port for Convex Peers
	 */
	public static final int DEFAULT_PEER_PORT = 18888;

	public static final int MAX_BIG_INTEGER_LENGTH = 4096;

	/**
	 * Flag to omit filling in stack traces on validation exceptions. This helps
	 * performance against DoS attacks
	 */
	public static final boolean OMIT_VALIDATION_STACKTRACES = true;

	public static final int PBE_ITERATIONS = 100000;
	
	public static final String DEFAULT_KEYSTORE_FILENAME = "~/.convex/keystore.pfx";

	/**
	 * Maximum depth of lookups via parent accounts
	 */
	public static final int LOOKUP_DEPTH = 16;

	/**
	 * SLIP-44 Chain code for Convex CVM
	 * 
	 * Convex Coin is coin type 864 in SLIP-0044 : 
	 * https://github.com/satoshilabs/slips/blob/master/slip-0044.md
	 */
	public static final int CHAIN_CODE = 864;

	/**
	 * Default derivation path for Convex keys
	 */
	public static final String DEFAULT_BIP39_PATH = "m/44/"+CHAIN_CODE+"/0/0/0";


}
