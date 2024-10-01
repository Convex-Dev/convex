package convex.core.cpos;

public class CPoSConstants {

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
	 * Number of consensus levels (blocks, proposed, consensus, finality)
	 */
	public static final int CONSENSUS_LEVELS = 4;
	public static final int CONSENSUS_LEVEL_PROPOSAL = CONSENSUS_LEVELS - 3;
	public static final int CONSENSUS_LEVEL_CONSENSUS = CONSENSUS_LEVELS - 2;
	public static final int CONSENSUS_LEVEL_FINALITY = CONSENSUS_LEVELS - 1;
	public static final boolean ENABLE_FORK_RECOVERY = false;
	/**
	 * Milliseconds before peer stake influence starts to decay (3 mins default)
	 */
	public static final double PEER_DECAY_DELAY = 3*60*1000;
	/**
	 * Time for peer stake to decay by factor 1/e (5 mins default)
	 */
	public static final double PEER_DECAY_TIME = 5*60*1000;
	/**
	 * Maximum time a block can be resurrected from the past (1 min)
	 */
	public static final long MAX_BLOCK_BACKDATE = 60*1000;

}