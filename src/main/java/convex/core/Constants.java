package convex.core;

import java.time.Instant;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;

/**
 * Static class for global configuration constants that affect protocol
 * behaviour
 */
public class Constants {

	public static final long MAX_SCHEDULED_TRANSACTIONS_PER_BLOCK = 100;

	public static final double PROPOSAL_THRESHOLD = 0.50;

	public static final double CONSENSUS_THRESHOLD = 0.67;

	public static final long INITIAL_TIMESTAMP = Instant.parse("2020-02-02T00:20:20.0202Z").toEpochMilli();

	public static final long INITIAL_JUICE_PRICE = 2L;
	
	/**
	 * Initial memory Pool of 1gb
	 */
	public static final long INITIAL_MEMORY_POOL = 1000000000L;

	/**
	 * Initial memory price per byte
	 */
	public static final long INITIAL_MEMORY_PRICE = 10L;

	public static final long MAX_TRANSACTION_JUICE = 1000000;

	public static final boolean ETCH_DELETE_TEMP_ON_EXIT = true;
	
	/**
	 * Sequence number used for any new account
	 */
	public static final long INITIAL_SEQUENCE = 0;

	/**
	 * Size in bytes of constant overhead applied per non-embedded Cell in memory accounting
	 */
	public static final long MEMORY_OVERHEAD = 64;

	public static final long DEFAULT_CLIENT_TIMEOUT = 10000;

	/**
	 * Allowance for initial user / peer accounts
	 */
	public static final long INITIAL_ACCOUNT_ALLOWANCE = 10000000;

	public static final long MAX_SUPPLY = 1000000000000000000L;

	public static final int MAX_DEPTH = 256;

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
}
