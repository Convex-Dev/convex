package convex.core;

import java.time.Instant;

/**
 * Static class for global configuration constants that affect protocol
 * behaviour
 */
public class Constants {

	public static final long MAX_SCHEDULED_TRANSACTIONS_PER_BLOCK = 100;

	public static final double PROPOSAL_THRESHOLD = 0.50;

	public static final double CONSENSUS_THRESHOLD = 0.70;

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

	/**
	 * Memory accounting on/off switch. If off, no memory accounting will be applied for
	 * transactions
	 */
	public static final boolean USE_MEMORY_ACCOUNTING=true;

	public static final long MAX_TRANSACTION_JUICE = 1000000;
	
	public static final boolean USE_ED25519=true;

	public static final boolean ETCH_DELETE_TEMP_ON_EXIT = true;
	
	/**
	 * Sequence number used to identify standard actors
	 */
	public static final long ACTOR_SEQUENCE = -1;

	/**
	 * Size in bytes of constant overhead applied per non-embedded Cell in memory accounting
	 */
	public static final long MEMORY_OVERHEAD = 64;

	public static final long DEFAULT_CLIENT_TIMEOUT = 10000;

	

}
