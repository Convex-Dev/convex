package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.lang.impl.RecordFormat;

/**
 * Class describing the on-chain state of a Peer declared on the network.
 *
 * State includes: - Stake placed by this Peer - A host address for peer
 * connections / client requests
 *
 */
public class PeerStatus extends ARecord {
	private static final Keyword[] PEER_KEYS = new Keyword[] { Keywords.CONTROLLER, Keywords.STAKE, Keywords.STAKES,Keywords.DELEGATED_STAKE,
			Keywords.METADATA};

	private static final RecordFormat FORMAT = RecordFormat.of(PEER_KEYS);

    private final Address controller;
	private final long stake;
	private final long delegatedStake;

	private final ABlobMap<Address, CVMLong> stakes;

	private final AHashMap<Keyword,ACell> metadata;

	private PeerStatus(Address controller, long stake, ABlobMap<Address, CVMLong> stakes, long delegatedStake, AHashMap<Keyword,ACell> metadata) {
		super(FORMAT);
        this.controller = controller;
		this.stake = stake;
		this.delegatedStake = delegatedStake;
		this.metadata = metadata;
		this.stakes = stakes;
	}

	public static PeerStatus create(Address controller, long stake) {
		return create(controller, stake, null);
	}

	public static PeerStatus create(Address controller, long stake, AHashMap<Keyword,ACell> metadata) {
		return new PeerStatus(controller, stake, BlobMaps.empty(), 0L, metadata);
	}
	/**
	 * Gets the stake of this peer
	 *
	 * @return Total stake, including own stake + delegated stake
	 */
	public long getTotalStake() {
		// TODO: include rewards?
		return stake + delegatedStake;
	}

	/**
	 * Gets the self-owned stake of this peer
	 *
	 * @return Own stake, excluding delegated stake
	 */
	public long getPeerStake() {
		// TODO: include rewards?
		return stake;
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
	 * Gets the delegated stake of this peer
	 *
	 * @return Total of delegated stake
	 */
	public long getDelegatedStake() {
		return delegatedStake;
	}

	/**
	 * Gets the String representation of the host address, or null if not specified
	 *
	 * @return Host String
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
	public AHashMap<Keyword, ACell> getMetadata() {
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
		pos = Format.writeVLCLong(bs,pos, stake);
		pos = Format.write(bs,pos, stakes);
		pos = Format.writeVLCLong(bs,pos, delegatedStake);
		pos = Format.write(bs,pos, metadata);
		return pos;
	}

	public static PeerStatus read(ByteBuffer bb) throws BadFormatException {
        Address owner = Format.read(bb);
		long stake = Format.readVLCLong(bb);
		ABlobMap<Address, CVMLong> stakes = Format.read(bb);
		long delegatedStake = Format.readVLCLong(bb);

		AHashMap<Keyword,ACell> metadata = Format.read(bb);

		return new PeerStatus(owner, stake,stakes,delegatedStake,metadata);
	}

	@Override
	public int estimatedEncodingSize() {
		return 100;
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
		long newDelegatedStake = delegatedStake + newStake - oldStake;

		ABlobMap<Address, CVMLong> newStakes = (newStake == 0L) ? stakes.dissoc(delegator)
				: stakes.assoc(delegator, CVMLong.create(newStake));
		return new PeerStatus(controller, stake, newStakes, newDelegatedStake, metadata);
	}
	
	/**
	 * Sets the Peer Stake on this peer for the given delegator.
	 *
	 * A value of 0 will remove the Peer stake entirely
	 *
	 * @param newStake New Delegated stake for the given Address
	 * @return Value of delegated stake
	 */
	public PeerStatus withPeerStake(long newStake) {
		if (stake == newStake) return this;

		return new PeerStatus(controller, newStake, stakes, delegatedStake, metadata);
	}

	public PeerStatus withHostname(AString newHostname) {
		AHashMap<Keyword,ACell> newMeta=metadata;
		if (newMeta==null) {
			newMeta=Maps.create(Keywords.URL, newHostname);
		} else {
			newMeta=newMeta.assoc(Keywords.URL, newHostname);
		}
		if (metadata==newMeta) return this;
		
		return new PeerStatus(controller, stake, stakes, delegatedStake, newMeta);
    }

	@Override
	public void validateCell() throws InvalidDataException {
		// TODO: Nothing?
	}

	@Override
	protected String ednTag() {
		return "#peer-status";
	}

	@Override
	public ACell get(ACell key) {
		if (Keywords.CONTROLLER.equals(key)) return controller;
		if (Keywords.STAKE.equals(key)) return CVMLong.create(stake);
		if (Keywords.STAKES.equals(key)) return stakes;
		if (Keywords.DELEGATED_STAKE.equals(key)) return CVMLong.create(delegatedStake);
		if (Keywords.METADATA.equals(key)) return metadata;

		return null;
	}

	@Override
	public byte getTag() {
		return Tag.PEER_STATUS;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected PeerStatus updateAll(ACell[] newVals) {
        Address newOwner = (Address) newVals[0];
		long newStake = ((CVMLong) newVals[1]).longValue();
		ABlobMap<Address, CVMLong> newStakes = (ABlobMap<Address, CVMLong>) newVals[2];
		long newDelStake = ((CVMLong) newVals[3]).longValue();
		AHashMap<Keyword,ACell> newMeta = (AHashMap<Keyword,ACell>) newVals[4];

		if ((this.stake==newStake)&&(this.stakes==newStakes)
				&&(this.metadata==newMeta)&&(this.delegatedStake==newDelStake)) {
			return this;
		}
		return new PeerStatus(newOwner, newStake, newStakes, newDelStake, newMeta);
	}

	protected static long computeDelegatedStake(ABlobMap<Address, CVMLong> stakes) {
		long ds = stakes.reduceValues((acc, e)->acc+e.longValue(), 0L);
		return ds;
	}
}
