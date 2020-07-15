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

	public static final long MAX_TRANSACTION_JUICE = 1000000;
	
	public static final boolean USE_ED25519=true;

}
