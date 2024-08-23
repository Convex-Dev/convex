package convex.core.data;

import convex.core.Constants;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.lang.RecordFormat;

/**
 * Class describing the on-chain state of a Peer declared on the network.
 *
 * State includes: - Stake placed by this Peer - A host address for peer
 * connections / client requests
 *
 */ 
public class PeerStatus extends ARecord {

	private static final Keyword[] PEER_KEYS = new Keyword[] { Keywords.CONTROLLER, Keywords.STAKE, Keywords.STAKES,Keywords.DELEGATED_STAKE,
			Keywords.METADATA, Keywords.TIMESTAMP,Keywords.BALANCE};

	private static final RecordFormat FORMAT = RecordFormat.of(PEER_KEYS);
	
	private static final Index<Address, CVMLong> EMPTY_STAKES = Index.none();

    private final Address controller;
	private final long peerStake;
	private final long delegatedStake;
	private final long timestamp;
	private final long balance;

	/**
	 * Map of delegated stakes. Never null internally, but empty map encoded as null.
	 */
	private final Index<Address, CVMLong> stakes;

	/**
	 * Metadata for the Peer. Can be null internally, which is interpreted as an empty Map.
	 */
	private final AHashMap<ACell,ACell> metadata;

	private PeerStatus(Address controller, long stake, Index<Address, CVMLong> stakes, long delegatedStake, AHashMap<ACell,ACell> metadata, long timestamp, long balance) {
		super(FORMAT.count());
        this.controller = controller;
		this.peerStake = stake;
		this.delegatedStake = delegatedStake;
		this.metadata = metadata;
		this.stakes = stakes;
		this.timestamp=timestamp;
		this.balance=balance;
	}

	public static PeerStatus create(Address controller, long stake) {
		return create(controller, stake, null);
	}

	public static PeerStatus create(Address controller, long stake, AHashMap<ACell,ACell> metadata) {
		return new PeerStatus(controller, stake, EMPTY_STAKES, 0L, metadata,Constants.INITIAL_PEER_TIMESTAMP,stake);
	}
	
	/**
	 * Gets the total stake shares for this peer
	 *
	 * @return Total stake, including own stake + delegated stake
	 */
	public long getTotalStake() {
		// TODO: include rewards?
		return peerStake + delegatedStake;
	}
	
    /**
	 * Gets the Convex Coin balance for this Peer. Owned jointly by peer and delegators
	 *
	 * @return The total Convex Coin balance of this peer
	 */
	public long getBalance() {
		return balance;
	}

	/**
	 * Gets the delegated stake of this peer
	 *
	 * @return Total of delegated stake
	 */
	public long getDelegatedStake() {
		return delegatedStake;
	}

	/**
	 * Gets the self-owned stake of this peer
	 *
	 * @return Own stake, excluding delegated stake
	 */
	public long getPeerStake() {
		// TODO: include rewards?
		return peerStake;
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
		if (metadata == null) return null;
		return RT.ensureString(metadata.get(Keywords.URL));
	}
	
	/**
	 * Gets the Metadata of this Peer
	 *
	 * @return Host String
	 */
	public AHashMap<ACell, ACell> getMetadata() {
		return metadata==null?Maps.empty():metadata;
	}
	

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.PEER_STATUS;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.write(bs,pos, controller);
		pos = Format.writeVLCLong(bs,pos, peerStake);
		if (stakes.isEmpty()) {
			bs[pos++]=Tag.NULL;
		} else {
			pos = Format.write(bs,pos, stakes);
		}
		pos = Format.writeVLCLong(bs,pos, delegatedStake);
		pos = Format.write(bs,pos, metadata);
		pos = Format.writeVLCLong(bs,pos, timestamp);
		pos = Format.writeVLCCount(bs,pos, balance);
		return pos;
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
		int epos=pos+1; // skip tag
	    Address owner = Format.read(b,epos);
	    epos+=Format.getEncodingLength(owner);
	    
	    long stake = Format.readVLCLong(b,epos);
	    epos+=Format.getVLCLength(stake);
	    
		Index<Address, CVMLong> stakes = Format.read(b,epos);
		epos+=Format.getEncodingLength(stakes);
		if (stakes==null) {
			stakes=EMPTY_STAKES;
		} else if (stakes.isEmpty()) {
			throw new BadFormatException("Empty delegated stakes should be encoded as null");
		}

		long delegatedStake = Format.readVLCLong(b,epos);
	    epos+=Format.getVLCLength(delegatedStake);
	    
		AHashMap<ACell,ACell> metadata = Format.read(b,epos);
		epos+=Format.getEncodingLength(metadata);
		
		long timestamp=Format.readVLCLong(b,epos);
		epos+=Format.getVLCLength(timestamp);
		
		long balance=Format.readVLCCount(b,epos);
		epos+=Format.getVLCCountLength(balance);
		 
		PeerStatus result= new PeerStatus(owner, stake,stakes,delegatedStake,metadata,timestamp,balance);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}

	@Override
	public int estimatedEncodingSize() {
		return stakes.estimatedEncodingSize()+100;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}


	/**
	 * Gets the delegated stake on this peer for the given delegator.
	 *
	 * Returns 0 if the delegator has no stake.
	 *
	 * @param delegator Address of delegator
	 * @return Value of delegated stake
	 */
	public long getDelegatedStake(Address delegator) {
		// TODO: include rewards?

		CVMLong a = stakes.get(delegator);
		if (a == null) return 0;
		return a.longValue();
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
		long oldStake = getDelegatedStake(delegator);
		if (oldStake == newStake) return this;

		// compute adjustment to total delegated stake
		long stakeChange=newStake-oldStake;
		long newDelegatedStake = delegatedStake + stakeChange;

		// Cast needed for Maven Java 11 compile?
		Index<Address, CVMLong> newStakes = (Index<Address,CVMLong>)((newStake == 0L) ? stakes.dissoc(delegator)
				: stakes.assoc(delegator, CVMLong.create(newStake)));
		return new PeerStatus(controller, peerStake, newStakes, newDelegatedStake, metadata,timestamp,balance+stakeChange);
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
		if (peerStake == newStake) return this;
		long stakeChange=newStake-peerStake;

		return new PeerStatus(controller, newStake, stakes, delegatedStake, metadata,timestamp,balance+stakeChange);
	}

	public PeerStatus withPeerData(AHashMap<ACell,ACell> newMeta) {
		if (metadata==newMeta) return this;	
		return new PeerStatus(controller, peerStake, stakes, delegatedStake, newMeta,timestamp,balance);
    }

	@Override
	public void validateCell() throws InvalidDataException {
		if (stakes==null) throw new InvalidDataException("Null stakes?",this);
		stakes.validateCell();
		if (metadata!=null) metadata.validateCell();
	}

	@Override
	public ACell get(Keyword key) {
		if (Keywords.CONTROLLER.equals(key)) return controller;
		if (Keywords.STAKE.equals(key)) return CVMLong.create(peerStake);
		if (Keywords.STAKES.equals(key)) return stakes;
		if (Keywords.DELEGATED_STAKE.equals(key)) return CVMLong.create(delegatedStake);
		if (Keywords.METADATA.equals(key)) return metadata;
		if (Keywords.TIMESTAMP.equals(key)) return CVMLong.create(timestamp);
		if (Keywords.BALANCE.equals(key)) return CVMLong.create(balance);

		return null;
	}

	@Override
	public byte getTag() {
		return Tag.PEER_STATUS;
	}

	@Override
	public PeerStatus updateRefs(IRefFunction func) {
		Index<Address, CVMLong> newStakes = Ref.updateRefs(stakes, func);
		AHashMap<ACell,ACell> newMeta = Ref.updateRefs(metadata, func);

		if ((this.stakes==newStakes)&&(this.metadata==newMeta)) {
			return this;
		}
		return new PeerStatus(controller, peerStake, newStakes, delegatedStake, newMeta,timestamp,balance);
	}

	protected static long computeDelegatedStake(Index<Address, CVMLong> stakes) {
		long ds = stakes.reduceValues((acc, e)->acc+e.longValue(), 0L);
		return ds;
	}

	@Override 
	public boolean equals(ACell a) {
		if (!(a instanceof PeerStatus)) return false;
		PeerStatus ps=(PeerStatus)a;
		return equals(ps);
	}
	
	/**
	 * Tests if this PeerStatus is equal to another
	 * @param a PeerStatus to compare with
	 * @return true if equal, false otherwise
	 */
	public boolean equals(PeerStatus a) {
		if (this == a) return true; // important optimisation for e.g. hashmap equality
		if (a == null) return false;
		Hash h=this.cachedHash();
		if (h!=null) {
			Hash ha=a.cachedHash();
			if (ha!=null) return Cells.equals(h, ha);
		}
		
		if (peerStake!=a.peerStake) return false;
		if (delegatedStake!=a.delegatedStake) return false;
		if (balance!=a.balance) return false;
		if (!(Cells.equals(stakes, a.stakes))) return false;
		if (!(Cells.equals(metadata, a.metadata))) return false;
		if (!(Cells.equals(controller, a.controller))) return false;
		return true;
	}

	@Override
	public int getRefCount() {
		int result=0;
		result+=Cells.refCount(stakes);
		result+=Cells.refCount(metadata);
		return result;
	}
	
	@Override 
	public <R extends ACell> Ref<R> getRef(int i) {
		int sc=Cells.refCount(stakes);
		if (i<sc) {
			return stakes.getRef(i);
		} else {
			if (metadata==null) throw new IndexOutOfBoundsException(i);
			return metadata.getRef(i-sc);
		}
	}

	@Override
	public RecordFormat getFormat() {
		return FORMAT;
	}

}
