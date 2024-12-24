package convex.core.cvm;

import convex.core.cpos.CPoSConstants;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.Utils;

/**
 * Class describing the on-chain state of a Peer declared on the network.
 *
 * State includes: - Stake placed by this Peer - A host address for peer
 * connections / client requests
 *
 */ 
public class PeerStatus extends ARecordGeneric {
	private static final Keyword[] PEER_KEYS = new Keyword[] { Keywords.CONTROLLER, Keywords.STAKE, Keywords.STAKES,Keywords.DELEGATED_STAKE,
			Keywords.METADATA, Keywords.TIMESTAMP,Keywords.BALANCE};
	private static final RecordFormat FORMAT = RecordFormat.of(PEER_KEYS);
	private static final Index<Address, CVMLong> EMPTY_STAKES = Index.none();

	private final int IX_TIMESTAMP=5;
	
	/**
	 * Per controller address
	 */
    private final Address controller;
    
    /**
     * Peer state share
     */
	private final long peerStake;

	/**
	 * Map of delegated stake shares. Never null internally, but empty map encoded as null.
	 */
	private Index<Address, CVMLong> stakes;

	/**
	 * Total of delegated stake shares
	 */
	private final long delegatedStake;

	/**
	 * Metadata for the Peer. Can be null internally, which is interpreted as an empty Map.
	 */
	private AHashMap<ACell,ACell> metadata;
	
	/**
	 * Timestamp of the latest block executed by the Peer
	 * 
	 * Maintain invariants:
	 *  PeerStatus time 
	 *  <= State timestamp (timestamp of last block of any peer, or INITIAL_TIMESTAMP)
	 *  <= peer timestamp (latest time recorded in Peer processing)
	 *  <= current time
	 */
	private final long timestamp;
	
	/**
	 * Convex coin balance of the peer, including accumulated rewards
	 */
	private final long balance;


	private PeerStatus(Address controller, long stake, Index<Address, CVMLong> stakes, long delegatedStake, AHashMap<ACell,ACell> metadata, long timestamp, long balance) {
		super(CVMTag.PEER_STATUS,FORMAT,Vectors.create(controller,CVMLong.create(stake),stakes,CVMLong.create(delegatedStake),metadata,CVMLong.create(timestamp),CVMLong.create(balance)));
        this.controller = controller;
		this.peerStake = stake;
		this.stakes = stakes;
		this.delegatedStake = delegatedStake;
		this.metadata = metadata;
		this.timestamp=timestamp;
		this.balance=balance;
	}

	public PeerStatus(AVector<ACell> values) {
		super(CVMTag.PEER_STATUS,FORMAT,values);
		this.controller = RT.ensureAddress(values.get(0));
		this.peerStake = RT.ensureLong(values.get(1)).longValue();
		this.delegatedStake = RT.ensureLong(values.get(3)).longValue();
		this.timestamp = RT.ensureLong(values.get(IX_TIMESTAMP)).longValue();
		this.balance = RT.ensureLong(values.get(6)).longValue();
	}

	public static PeerStatus create(Address controller, long stake) {
		return create(controller, stake, null);
	}

	public static PeerStatus create(Address controller, long stake, AHashMap<ACell,ACell> metadata) {
		return new PeerStatus(controller, stake, EMPTY_STAKES, 0L, metadata,CPoSConstants.INITIAL_PEER_TIMESTAMP,stake);
	}
	
	/**
	 * Gets the total stake shares for this peer (excluding accumulated balance)
	 *
	 * @return Total stake shares, including own stake + delegated stake shares
	 */
	public long getTotalStake() {
		return peerStake + delegatedStake;
	}
	
    /**
	 * Gets the Convex Coin balance for this Peer. Owned jointly by peer and delegators according to shares
	 *
	 * @return The total Convex Coin balance of this peer
	 */
	public long getBalance() {
		return balance;
	}

	/**
	 * Gets the total delegated stake of this peer 
	 *
	 * @return Total of delegated stake
	 */
	public long getDelegatedStake() {
		long totalShares=peerStake+delegatedStake;
		if (totalShares<=0) return 0; // nobody has any stake. Negative should not be possible, just in case
		return Utils.mulDiv(balance,delegatedStake,totalShares);
	}
	
	public Index<Address,CVMLong> getStakes() {
		if (stakes==null) stakes=RT.ensureIndex(values.get(2));
		return stakes;
	}

	/**
	 * Gets the self-owned stake of this peer, including accumulated balance
	 *
	 * @return Own stake, excluding delegated stake
	 */
	public long getPeerStake() {
		// Peer stake is what remains after delegated stake shares
		return balance-getDelegatedStake();
	}
	
	/**
	 * Gets the delegated stake on this peer for the given delegator, including accumulated balance
	 *
	 * Returns 0 if the delegator has no stake.
	 *
	 * @param delegator Address of delegator
	 * @return Value of delegated stake
	 */
	public long getDelegatedStake(Address delegator) {
		if (delegatedStake<=0) return 0; // nobody has any delegated stake. Negative should not be possible, just in case
		Index<Address, CVMLong> stks = getStakes();
		if (stks==null) return 0;
		CVMLong a = stks.get(delegator);
		if (a == null) return 0;
		
		long delShares=a.longValue();
		return Utils.mulDiv(balance-getPeerStake(),delShares,delegatedStake);
	}
	
	/**
	 * Gets the timestamp of the last Block issued by this Peer in consensus
	 *
	 * @return Timestamp of last block, or -1 if no block yet issued.
	 */
	public long getTimestamp() {
		return timestamp;
	}

    /**
	 * Gets the controller of this peer
	 *
	 * @return The controller of this peer
	 */
	public Address getController() {
		return controller;
	}
	

	/**
	 * Gets the String representation of the hostname set for the current Peer status, 
	 * or null if not specified.
	 *
	 * @return Hostname String
	 */
	public AString getHostname() {
		AHashMap<ACell, ACell> meta = getMetadata();
		if (meta == null) return null;
		return RT.ensureString(meta.get(Keywords.URL));
	}
	
	/**
	 * Gets the Metadata of this Peer
	 *
	 * @return Host String
	 */
	@SuppressWarnings("unchecked")
	public AHashMap<ACell, ACell> getMetadata() {
		if (metadata==null) {
			metadata=(AHashMap<ACell, ACell>)(values.get(4));
		}
		return metadata;
	}

	/**
	 * Decodes a PeerStatus from a Blob.
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static PeerStatus read(Blob b, int pos) throws BadFormatException{
		AVector<ACell> values=Vectors.read(b, pos);
		int epos=pos+values.getEncodingLength();
		
		PeerStatus result=new PeerStatus(values);
		result.attachEncoding(b.slice(pos,epos));
		return result;

	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	/**
	 * Sets the delegated stake on this peer for the given delegator.
	 *
	 * A value of 0 will remove the delegator's stake entirely
	 *
	 * @param delegator Address of delegator
	 * @param newStake New Delegated stake for the given Address
	 * @return Value of delegated stake
	 */
	public PeerStatus withDelegatedStake(Address delegator, long newStake) {
		if (newStake<0L) throw new IllegalArgumentException("Negative peer stake!");

		long oldStake = getDelegatedStake(delegator);
		if (oldStake == newStake) return this;

		// compute adjustment to total delegated stake
		long stakeChange=newStake-oldStake;
		long newDelegatedStake = delegatedStake + stakeChange;

		// Cast needed for Maven Java 11 compile?
		Index<Address, CVMLong> stks = getStakes();
		if (stks==null) stks=EMPTY_STAKES;
		Index<Address, CVMLong> newStakes = (Index<Address,CVMLong>)((newStake == 0L) ? stks.dissoc(delegator)
				: stks.assoc(delegator, CVMLong.create(newStake)));
		if (newStakes.isEmpty()) newStakes=null;
		
		return new PeerStatus(controller, peerStake, newStakes, newDelegatedStake, metadata,timestamp,balance+stakeChange);
	}
	
	private PeerStatus withBalance(long newBalance) {
		if (balance==newBalance) return this;
		return new PeerStatus(values.assoc(6,CVMLong.create(newBalance)));
	}
	
	private PeerStatus withTimestamp(long newTimestamp) {
		if (timestamp==newTimestamp) return this;
		return new PeerStatus(values.assoc(IX_TIMESTAMP,CVMLong.create(newTimestamp)));
	}
	
	/**
	 * Sets the Peer Stake on this peer for the given delegator.
	 *
	 * A value of 0 will remove the Peer stake entirely
	 *
	 * @param newStake New Peer stake 
	 * @return Updates PeerStatus
	 */
	public PeerStatus withPeerStake(long newStake) {
		if (newStake<0L) throw new IllegalArgumentException("Negative peer stake!");

		if (peerStake == newStake) return this;
		long stakeChange=newStake-peerStake;

		return new PeerStatus(controller, newStake, getStakes(), delegatedStake, getMetadata(),timestamp,balance+stakeChange);
	}

	public PeerStatus withPeerData(AHashMap<ACell,ACell> newMeta) {
		if (metadata==newMeta) return this;	
		return new PeerStatus(controller, peerStake, getStakes(), delegatedStake, newMeta,timestamp,balance);
    }
	


	@Override
	public void validateCell() throws InvalidDataException {
		if (balance<0L) throw new InvalidDataException("Negative balance?",this);
		if (delegatedStake<0L) throw new InvalidDataException("Negative delegated stake?",this);
		if (peerStake<0L) throw new InvalidDataException("Negative peer stake?",this);
	}

	@Override
	public ACell get(Keyword key) {
		if (Keywords.CONTROLLER.equals(key)) return controller;
		if (Keywords.STAKE.equals(key)) return values.get(1);
		if (Keywords.STAKES.equals(key)) return getStakes();
		if (Keywords.DELEGATED_STAKE.equals(key)) return values.get(3);
		if (Keywords.METADATA.equals(key)) return getMetadata();
		if (Keywords.TIMESTAMP.equals(key)) return values.get(5);
		if (Keywords.BALANCE.equals(key)) return values.get(6);

		return null;
	}

	protected static long computeDelegatedStake(Index<Address, CVMLong> stks) {
		long ds = stks.reduceValues((acc, e)->acc+e.longValue(), 0L);
		return ds;
	}

	@Override 
	public boolean equals(ACell a) {
		if (a instanceof PeerStatus) return equals((PeerStatus)a);
		return Cells.equalsGeneric(this, a);
	}
	
	/**
	 * Tests if this PeerStatus is equal to another
	 * @param a PeerStatus to compare with
	 * @return true if equal, false otherwise
	 */
	public boolean equals(PeerStatus a) {
		if (this == a) return true; // important optimisation for e.g. hashmap equality
		return Cells.equalsGeneric(a, a);
	}

	public PeerStatus addReward(long peerFees) {
		if (peerFees<0) throw new IllegalArgumentException("Negative fees!");
		return withBalance(balance+peerFees);
	}

	@Override
	protected ARecordGeneric withValues(AVector<ACell> newValues) {
		if (values==newValues) return this;
		return new PeerStatus(newValues);
	}

	public PeerStatus distributeBlockReward(State state, long peerFees, long newBlockTime) {
		PeerStatus ps=addReward(peerFees);
		long oldTime=ps.getTimestamp();
		
		// Maybe bump timestamp
		if (oldTime<newBlockTime) {
			ps=ps.withTimestamp(newBlockTime);
		}
		
		return ps;
	}

	

}
