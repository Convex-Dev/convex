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
	 * Number of consensus levels used by the Convex main network protocol
	 * (ordering, proposal, consensus, finality).
	 *
	 * <p>Encoded Orders may supply fewer or additional levels. Consensus uses
	 * this configured prefix: missing confirmation levels contribute zero and
	 * surplus levels are ignored.</p>
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
	 * Minimum proportion of stake that a peer can decay to
	 */
	public static final double PEER_DECAY_MINIMUM = 0.001;
	
	/**
	 * Maximum time a block can be resurrected from the past (15 min)
	 */
	public static final long MAX_BLOCK_BACKDATE = 15*60*1000;

	/**
	 * Clock-skew allowance for confirming forward-dated blocks (30 seconds).
	 *
	 * <p>A peer declines to advance consensus (and hence execution) past any block
	 * dated further ahead than its own wall clock plus this allowance, until its clock
	 * catches up (#595 stage (i), see convex-core/docs/CONSENSUS.md). This prevents a
	 * future-dated block from teleporting the consensus clock forward and firing
	 * scheduled network upgrades early. Deliberately much tighter than
	 * {@link #MAX_BLOCK_BACKDATE}: forward-dating is the attack surface, backdating is
	 * harmless.</p>
	 */
	public static final long MAX_BLOCK_FORWARD = 30*1000;
	
	/**
	 * Initial timestamp for a Peer before it has any blocks
	 */
	public static final long INITIAL_PEER_TIMESTAMP = -1L;
	
	/**
	 * Minimum stake balance for a Peer to be considered by other Peers in consensus
	 */
	public static final long MINIMUM_EFFECTIVE_STAKE = Coin.GOLD * 1000;
	/**
	 * Minimum milliseconds to retain a proposal before switching
	 */
	public static final long KEEP_PROPOSAL_TIME = 100;
	
	/**
	 * Memory allowance for genesis user / peer accounts
	 */
	public static final long INITIAL_ACCOUNT_ALLOWANCE = 10000;
	
	/**
	 * Maximum allowed encoded peer message length in bytes (50mb)
	 */
	public static final long MAX_MESSAGE_LENGTH = 50000000;
	
	/**
	 * Maximum allowed number of missing hashes in missing data request
	 * 
	 * (2 header values short of 256, so that request vector is 2 levels at max size)
	 */
	public static final int MISSING_LIMIT = 254;
	
	/**
	 * Milliseconds time between blocks for a peer to collect maximum rewards (10 mins)
	 */
	public static final long MAX_REWARD_TIME = 10*60*1000;


}
