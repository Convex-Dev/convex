package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.impl.RecordFormat;

/**
 * Class describing the on-chain state of a Peer declared on the network.
 * 
 * State includes: - Stake placed by this Peer - A host address for peer
 * connections / client requests
 *
 */
public class PeerStatus extends ARecord {
	private static final Keyword[] PEER_KEYS = new Keyword[] { Keywords.STAKE, Keywords.STAKES,Keywords.DELEGATED_STAKE,
			Keywords.URL};

	private static final RecordFormat FORMAT = RecordFormat.of(PEER_KEYS);
	
	private final Amount stake;
	private final Amount delegatedStake;

	private final ABlobMap<Address, Amount> stakes;

	private final AString hostAddress;

	private PeerStatus(Amount stake, ABlobMap<Address, Amount> stakes, Amount delegatedStake, AString host) {
		super(FORMAT);
		this.stake = stake;
		this.delegatedStake = delegatedStake;
		this.hostAddress = host;
		this.stakes = stakes;
	}

	public static PeerStatus create(Amount stake) {
		return create(stake, null);
	}

	public static PeerStatus create(Amount stake, AString hostString) {
		return new PeerStatus(stake, BlobMaps.empty(), Amount.ZERO, hostString);
	}
	/**
	 * Gets the stake of this peer
	 * 
	 * @return Total stake, including own stake + delegated stake
	 */
	public long getTotalStake() {
		return stake.getValue() + delegatedStake.getValue();
	}

	/**
	 * Gets the self-owned stake of this peer
	 * 
	 * @return Own stake, excluding delegated stake
	 */
	public long getOwnStake() {
		return stake.getValue();
	}

	/**
	 * Gets the delegated stake of this peer
	 * 
	 * @return Total of delegated stake
	 */
	public long getDelegatedStake() {
		return delegatedStake.getValue();
	}

	/**
	 * Gets the String representation of the host address, or null if not specified
	 * 
	 * @return Host String
	 */
	public AString getHostString() {
		if (hostAddress == null) return null;
		return hostAddress;
	}

	@Override
	public int write(byte[] bs, int pos) {
		bs[pos++]=Tag.PEER_STATUS;
		return writeRaw(bs,pos);
	}

	@Override
	public int writeRaw(byte[] bs, int pos) {
		pos = Format.write(bs,pos, stake);
		pos = Format.write(bs,pos, stakes);
		pos = Format.write(bs,pos, delegatedStake);
		pos = Format.write(bs,pos, getHostString());
		return pos;
	}

	public static PeerStatus read(ByteBuffer data) throws BadFormatException {
		Amount stake = Format.read(data);
		ABlobMap<Address, Amount> stakes = Format.read(data);
		Amount delegatedStake = Format.read(data);
		
		AString hostString = Format.read(data);
		
		return new PeerStatus(stake,stakes,delegatedStake,hostString);
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
		Amount a = stakes.get(delegator);
		if (a == null) return 0;
		return a.getValue();
	}

	/**
	 * Sets the delegated stake on this peer for the given delegator.
	 * 
	 * A value of 0 will remove the delegator's stake entirely
	 * 
	 * @param delegator Address of delegator
	 * @return Value of delegated stake
	 */
	public PeerStatus withDelegatedStake(Address delegator, long newStake) {
		Amount newAmount = Amount.create(newStake); // throws if negative / out of range. Caller messed up!
		long oldStake = getDelegatedStake(delegator);
		if (oldStake == newStake) return this;

		// compute adjustment to total delegated stake
		Amount newDelegatedStake = Amount.create(delegatedStake.getValue() + newStake - oldStake);

		ABlobMap<Address, Amount> newStakes = (newStake == 0) ? stakes.dissoc(delegator)
				: stakes.assoc(delegator, newAmount);
		return new PeerStatus(stake, newStakes, newDelegatedStake, hostAddress);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		stake.validateCell();
		delegatedStake.validateCell();
	}

	@Override
	protected String ednTag() {
		return "#peer-status";
	}

	@SuppressWarnings("unchecked")
	@Override
	public <V> V get(Keyword key) {
		if (Keywords.STAKE.equals(key)) return (V) stake;
		if (Keywords.STAKES.equals(key)) return (V) stakes;
		if (Keywords.DELEGATED_STAKE.equals(key)) return (V) delegatedStake;
		if (Keywords.URL.equals(key)) return (V) hostAddress;
		
		return null;
	}

	@Override
	public byte getRecordTag() {
		return Tag.PEER_STATUS;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected PeerStatus updateAll(Object[] newVals) {
		Amount newStake = (Amount) newVals[0];
		ABlobMap<Address, Amount> newStakes = (ABlobMap<Address, Amount>) newVals[1];
		Amount newDelStake = (Amount) newVals[2];
		AString newHostAddress = (AString) newVals[3];
		
		if ((this.stake==newStake)&&(this.stakes==newStakes)
				&&(this.hostAddress==newHostAddress)&&(this.delegatedStake==newDelStake)) {
			return this;
		}
		return new PeerStatus(newStake, newStakes, newDelStake, newHostAddress);
	}

	protected static Amount computeDelegatedStake(ABlobMap<Address, Amount> stakes) {
		long ds = stakes.reduceValues((acc, e)->acc+e.getValue(), 0L);
		return Amount.create(ds);
	}

}
