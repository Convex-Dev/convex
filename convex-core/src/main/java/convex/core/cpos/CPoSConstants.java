package convex.core.cpos;

import convex.core.Coin;

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
	 * Time for peer stake to decay by factor 1/e (30 mins default)
	 */
	public static final double PEER_DECAY_TIME = 5*60*1000;
	
	/**
	 * Minimum proportion of stake that a peer can decay to
	 */
	public static final double PEER_DECAY_MINIMUM = 0.001;
	
	/**
	 * Maximum time a block can be resurrected from the past (15 min)
	 */
	public static final long MAX_BLOCK_BACKDATE = 15*60*1000;
	
	/**
	 * Initial timestamp for a Peer before it has any blocks
	 */
	public static final long INITIAL_PEER_TIMESTAMP = -1L;
	
	/**
	 * Minimum stake for a Peer to be considered by other Peers in consensus
	 */
	public static final long MINIMUM_EFFECTIVE_STAKE = Coin.GOLD * 1000;
	/**
	 * Minimum milliseconds to retain a proposal before switching
	 */
	public static final long KEEP_PROPOSAL_TIME = 100;
	/**
	 * Memory allowance for genesis user / peer accounts
	 */
	public static final long INITIAL_ACCOUNT_ALLOWANCE = 1000000;
	
	/**
	 * Maximum allowed encoded peer message length in bytes (50mb)
	 */
	public static final long MAX_MESSAGE_LENGTH = 50000000;


}
