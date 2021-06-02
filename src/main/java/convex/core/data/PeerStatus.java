package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.data.prim.CVMLong;
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

	private final long stake;
	private final long delegatedStake;

	private final ABlobMap<Address, CVMLong> stakes;

	private final AString hostname;

	private PeerStatus(long stake, ABlobMap<Address, CVMLong> stakes, long delegatedStake, AString host) {
		super(FORMAT);
		this.stake = stake;
		this.delegatedStake = delegatedStake;
		this.hostname = host;
		this.stakes = stakes;
	}

	public static PeerStatus create(long stake) {
		return create(stake, null);
	}

	public static PeerStatus create(long stake, AString hostString) {
		return new PeerStatus(stake, BlobMaps.empty(), 0L, hostString);
	}
	/**
	 * Gets the stake of this peer
	 *
	 * @return Total stake, including own stake + delegated stake
	 */
	public long getTotalStake() {
		return stake + delegatedStake;
	}

	/**
	 * Gets the self-owned stake of this peer
	 *
	 * @return Own stake, excluding delegated stake
	 */
	public long getOwnStake() {
		return stake;
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
		if (hostname == null) return null;
		return hostname;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.PEER_STATUS;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.writeVLCLong(bs,pos, stake);
		pos = Format.write(bs,pos, stakes);
		pos = Format.writeVLCLong(bs,pos, delegatedStake);
		pos = Format.write(bs,pos, getHostname());
		return pos;
	}

	public static PeerStatus read(ByteBuffer data) throws BadFormatException {
		long stake = Format.readVLCLong(data);
		ABlobMap<Address, CVMLong> stakes = Format.read(data);
		long delegatedStake = Format.readVLCLong(data);

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
	 * @return Value of delegated stake
	 */
	public PeerStatus withDelegatedStake(Address delegator, long newStake) {
		long oldStake = getDelegatedStake(delegator);
		if (oldStake == newStake) return this;

		// compute adjustment to total delegated stake
		long newDelegatedStake = delegatedStake + newStake - oldStake;

		ABlobMap<Address, CVMLong> newStakes = (newStake == 0L) ? stakes.dissoc(delegator)
				: stakes.assoc(delegator, CVMLong.create(newStake));
		return new PeerStatus(stake, newStakes, newDelegatedStake, hostname);
	}

	public PeerStatus withHostname(AString hostname) {
		return new PeerStatus(stake, stakes, delegatedStake, hostname);
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
		if (Keywords.STAKE.equals(key)) return CVMLong.create(stake);
		if (Keywords.STAKES.equals(key)) return stakes;
		if (Keywords.DELEGATED_STAKE.equals(key)) return CVMLong.create(delegatedStake);
		if (Keywords.URL.equals(key)) return hostname;

		return null;
	}

	@Override
	public byte getTag() {
		return Tag.PEER_STATUS;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected PeerStatus updateAll(ACell[] newVals) {
		long newStake = ((CVMLong) newVals[0]).longValue();
		ABlobMap<Address, CVMLong> newStakes = (ABlobMap<Address, CVMLong>) newVals[1];
		long newDelStake = ((CVMLong) newVals[2]).longValue();
		AString newHostname = (AString) newVals[3];

		if ((this.stake==newStake)&&(this.stakes==newStakes)
				&&(this.hostname==newHostname)&&(this.delegatedStake==newDelStake)) {
			return this;
		}
		return new PeerStatus(newStake, newStakes, newDelStake, newHostname);
	}

	protected static long computeDelegatedStake(ABlobMap<Address, CVMLong> stakes) {
		long ds = stakes.reduceValues((acc, e)->acc+e.longValue(), 0L);
		return ds;
	}
}
