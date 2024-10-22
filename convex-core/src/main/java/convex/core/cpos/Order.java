package convex.core.cpos;

import convex.core.data.ACell;
import convex.core.data.ARecord;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.RecordFormat;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;

/**
 * Class representing an Ordering of transactions, along with the consensus position.
 * 
 * An Ordering contains: 
 * <ul>
 * <li>The Vector of known verified Blocks announced by the Peer</li>
 * <li>The proposed consensus point (point at which the peer believes there is sufficient
 * alignment for consensus)</li>
 * <li>The current consensus point (point at which the
 * peer has observed sufficient consistent consensus proposals)</li>
 * </ul>
 * 
 * An Ordering is immutable.
 * 
 */
public class Order extends ARecord {
	/**
	 * Ref to Blocks Vector. We use Ref to assist de-duplication, since many Orders
	 * likely to share same Blocks value
	 */
	private final Ref<AVector<SignedData<Block>>> blocks;

	/**
	 * Array of consensus points for each consensus level. The first element (block count)
	 * is ignored.
	 */
	private final long [] consensusPoints;
	
	/**
	 * Timestamp of this Order
	 */
	private final long timestamp;

	private static final Keyword[] KEYS = new Keyword[] { Keywords.BLOCKS, Keywords.CONSENSUS_POINT, Keywords.PROPOSAL_POINT , Keywords.TIMESTAMP};
	private static final RecordFormat FORMAT = RecordFormat.of(KEYS);

	private static final long[] EMPTY_CONSENSUS_ARRAY = new long[CPoSConstants.CONSENSUS_LEVELS];

	private Order(Ref<AVector<SignedData<Block>>> blocks, long[] consensusPoints, long timestamp) {
		super(FORMAT.count());
		this.blocks = blocks;
		this.consensusPoints=consensusPoints;
		this.timestamp = timestamp;
	}

	/**
	 * Create an Order
	 * @param blocks Blocks in Order
	 * @param proposalPoint Proposal Point
	 * @param consensusPoint Consensus Point
	 * @return New Order instance
	 */
	private static Order create(Ref<AVector<SignedData<Block>>> blocks, long proposalPoint, long consensusPoint, long timestamp) {
		long[] consensusPoints=new long[CPoSConstants.CONSENSUS_LEVELS];
		consensusPoints[0] = blocks.getValue().count();
		consensusPoints[1] = proposalPoint;
		consensusPoints[2] = consensusPoint;

		return new Order(blocks, consensusPoints,timestamp);
	}

	/**
	 * Create an Order with the given consensus positions and Blocks. Mainly for testing.
	 * @param proposalPoint Proposal Point
	 * @param consensusPoint Consensus Point
	 * @param blocks Blocks in Order

	 * @return New Order instance
	 */
	@SuppressWarnings("unchecked")
	public static Order create(long proposalPoint, long consensusPoint, SignedData<Block>... blocks ) {
		return create(Vectors.of((Object[])blocks).getRef(), proposalPoint, consensusPoint,0);
	}
	
	/**
	 * Create an empty Order

	 * @return New Order instance
	 */
	public static Order create() {
		return new Order(Vectors.empty().getRef(), EMPTY_CONSENSUS_ARRAY,0);
	}

	private byte getRecordTag() {
		return Tag.ORDER;
	}
	
	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=getRecordTag();
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = blocks.encode(bs,pos);
		for (int level=1; level<CPoSConstants.CONSENSUS_LEVELS; level++) {
			pos = Format.writeVLQLong(bs,pos, consensusPoints[level]);
		}
		pos = Format.writeVLQLong(bs,pos, timestamp);
		return pos;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return blocks.estimatedEncodingSize()+30; // blocks plus enough size for points
	}

	/**
	 * Decode an Order from a Blob encoding
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static Order read(Blob b, int pos) throws BadFormatException {
		int epos=pos+1; // skip tag
		Ref<AVector<SignedData<Block>>> blocks = Format.readRef(b,epos);
		if (blocks==null) {
			throw new BadFormatException("Null blocks in Order!");
		}
		epos+=blocks.getEncodingLength();
		
		long[] cps=new long[CPoSConstants.CONSENSUS_LEVELS];
		long last=Long.MAX_VALUE;
		for (int level=1; level<CPoSConstants.CONSENSUS_LEVELS; level++) {
			long pp = Format.readVLQLong(b,epos);
			cps[level]=pp;
			epos+=Format.getVLQLongLength(pp);
			if (pp>last) {
				throw new BadFormatException("Consensus point ["+pp+"] before previous value [" + last+"] at level "+level);
			}
			last=pp;
		}
		long ts = Format.readVLQLong(b,epos); // TODO: should just be 8 bytes?
		epos+=Format.getVLQLongLength(ts);
		
		Order result=new Order(blocks, cps,ts);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}

	/**
	 * Checks if another Order is consistent with this Order.
	 * 
	 * Order is defined as consistent iff: 
	 * <ul>
	 * <li> Blocks are equal up to the Consensus
	 * Point of this Order
	 * </li>
	 * </ul>
	 * 
	 * @param bc Order to compare with
	 * @return True if chains are consistent, false otherwise.
	 */
	public boolean checkConsistent(Order bc) {
		long commonPrefix = getBlocks().commonPrefixLength(bc.getBlocks());
		return commonPrefix >= getConsensusPoint();
	}


	
	/**
	 * Gets the Consensus Point of this Order for the specified level
	 * @param level Consensus level
	 * @return Consensus Point
	 */
	public long getConsensusPoint(int level) {
		if (level==0) return blocks.getValue().count();
		return consensusPoints[level];
	}
	
	public long[] getConsensusPoints() {
		long[] result=consensusPoints.clone();
		result[0]=getBlockCount();
		return result;
	}

	/**
	 * Gets the Proposal Point of this Order
	 * @return Proposal Point
	 */
	public long getProposalPoint() {
		return consensusPoints[CPoSConstants.CONSENSUS_LEVEL_PROPOSAL];
	}
	
	/**
	 * Gets the Consensus Point of this Order
	 * @return Consensus Point
	 */
	public long getConsensusPoint() {
		return consensusPoints[CPoSConstants.CONSENSUS_LEVEL_CONSENSUS];
	}
	
	/**
	 * Gets the timestamp of this Order
	 * @return Proposal Point
	 */
	public long getTimestamp() {
		return timestamp;
	}

	/**
	 * Gets the Blocks in this Order
	 * @return Vector of Blocks
	 */
	public AVector<SignedData<Block>> getBlocks() {
		return blocks.getValue();
	}

	/**
	 * Get a specific Block in this Order
	 * @param i Index of Block
	 * @return Block at specified index.
	 */
	public SignedData<Block> getBlock(long i) {
		return getBlocks().get(i);
	}

	/**
	 * Append a new block of transactions in this Order
	 * 
	 * @param block Block to append
	 * @return The updated chain
	 */
	public Order append(SignedData<Block> block) {
		AVector<SignedData<Block>> newBlocks = getBlocks().append(block);
		return withBlocks(newBlocks);
	}

	/**
	 * Updates blocks in this Order. Returns the same Order if the blocks are identical.
	 * @param newBlocks New blocks to use
	 * @return Updated Order, or the same order if unchanged
	 */
	public Order withBlocks(AVector<SignedData<Block>> newBlocks) {
		if (blocks.getValue() == newBlocks) return this;
		
		// Update proposal point and consensus point if necessary to ensure consistency
		long nblocks=newBlocks.count();
		long newProposalPoint = Math.min(nblocks, getProposalPoint());
		long newConsensusPoint = Math.min(nblocks, getConsensusPoint());
		
		return create(newBlocks.getRef(), newProposalPoint, newConsensusPoint, timestamp);
	}
	
	/**
	 * Updates timestamp in this Order. Returns the same Order if timestamp is identical.
	 * @param newTimestamp New timestamp to use
	 * @return Updated Order, or the same Order if unchanged
	 */
	public Order withTimestamp(long newTimestamp) {
		if (timestamp == newTimestamp) return this;
		return new Order(blocks, consensusPoints, newTimestamp);
	}
	
	/**
	 * Updates this Order with a new consensus position.
	 * 
	 * @param level Consensus level to update
	 * @param newPosition New consensus point
	 * @return Updated Order, or this Order instance if no change.
	 */
	public Order withConsensusPoint(int level,long newPosition) {
		if (level==0) return this; // TODO: sane or not?
		if (consensusPoints[level]==newPosition) return this;
		long[] cps=consensusPoints.clone();
		cps[0]=getBlockCount();
		cps[level]=newPosition;
		return new Order(blocks,cps,timestamp);
	}
	
	/**
	 * Updates this Order with new consensus positios.
	 * 
	 * @param newPositions New consensus points
	 * @return Updated Order, or this Order instance if no change.
	 */
	public Order withConsensusPoints(long[] newPositions) {
		long[] cps=newPositions.clone();
		cps[0]=getBlockCount();
		return new Order(blocks,cps,timestamp);
	}

	/**
	 * Get the number of Blocks in this Order
	 * @return Number of Blocks
	 */
	public long getBlockCount() {
		return getBlocks().count();
	}

	/**
	 * Clears the consensus and proposal point
	 * @return Updated order with zeroed consensus positions
	 */
	public Order withoutConsenus() {
		return new Order(blocks, EMPTY_CONSENSUS_ARRAY,timestamp);
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		blocks.validate();
	}

	@Override
	public void validateCell() throws InvalidDataException {

	}

	@Override
	public int getRefCount() {
		return 1;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		if (i==0) return (Ref<R>) blocks;
		throw new IndexOutOfBoundsException(i);
	}
	
	@Override
	public byte getTag() {
		return Tag.ORDER;
	}

	@Override
	public ACell get(Keyword key) {
		if (Keywords.BLOCKS.equals(key)) return getBlocks();
		if (Keywords.CONSENSUS_POINT.equals(key)) return CVMLong.create(getConsensusPoint());
		if (Keywords.PROPOSAL_POINT.equals(key)) return CVMLong.create(getProposalPoint());
		if (Keywords.TIMESTAMP.equals(key)) return CVMLong.create(timestamp);

		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Order updateRefs(IRefFunction func) {
		Ref<AVector<SignedData<Block>>> newBlocks = (Ref<AVector<SignedData<Block>>>) func.apply(blocks);
		if (blocks == newBlocks) {
			return this;
		}
		return new Order(newBlocks, consensusPoints, timestamp);
	}

	/**
	 * Tests if this Order is equivalent to another in terms of consensus (timestamp ignored)
	 * @param b Order to compare with
	 * @return True if Orders are functionally equal, false otherwise
	 */
	public boolean consensusEquals(Order b) {
		if (b==null) return false; // definitely not equal
		for (int i=1; i<CPoSConstants.CONSENSUS_LEVELS; i++) {
			if (this.getConsensusPoint(i)!=b.getConsensusPoint(i)) return false;			
		}
		if (!this.blocks.equals(b.blocks)) return false;
		return true;
	}

	@Override
	public RecordFormat getFormat() {
		return FORMAT;
	}
	
	@Override
	public boolean equals(ACell o) {
		return ACell.genericEquals(this, o);
	}

}
