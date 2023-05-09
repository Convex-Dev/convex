package convex.core;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.ARecord;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.impl.RecordFormat;

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
	private final Ref<AVector<SignedData<Block>>> blocks;

	private final long proposalPoint;
	private final long consensusPoint;
	private final long timestamp;

	private static final Keyword[] KEYS = new Keyword[] { Keywords.BLOCKS, Keywords.CONSENSUS_POINT, Keywords.PROPOSAL_POINT , Keywords.TIMESTAMP};
	private static final RecordFormat FORMAT = RecordFormat.of(KEYS);

	private Order(Ref<AVector<SignedData<Block>>> blocks, long proposalPoint, long consensusPoint, long timestamp) {
		super(FORMAT.count());
		this.blocks = blocks;
		this.consensusPoint = consensusPoint;
		this.proposalPoint = proposalPoint;
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
		return new Order(blocks, proposalPoint, consensusPoint,timestamp);
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
		return create(Vectors.empty().getRef(), 0, 0,0);
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
		pos = Format.writeVLCLong(bs,pos, proposalPoint);
		pos = Format.writeVLCLong(bs,pos, consensusPoint);
		pos = Format.writeVLCLong(bs,pos, timestamp);
		return pos;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return blocks.estimatedEncodingSize()+30; // blocks plus enough size for points
	}

	/**
	 * Decode an Order from a ByteBuffer
	 * @param bb ByteBuffer to read from
	 * @return Order instance
	 * @throws BadFormatException If encoding format is invalid
	 */
	public static Order read(ByteBuffer bb) throws BadFormatException {
		Ref<AVector<SignedData<Block>>> blocks = Format.readRef(bb);
		if (blocks==null) {
			throw new BadFormatException("Null blocks in Order!");
		}
		
		long pp = Format.readVLCLong(bb);
		long cp = Format.readVLCLong(bb);
		long ts = Format.readVLCLong(bb);
		
		if (pp<cp) {
			throw new BadFormatException("Proposal point ["+pp+"] before consensus point [" + cp+"]");
		}

		return new Order(blocks, pp, cp,ts);
	}
	

	public static Order read(Blob b, int pos) throws BadFormatException {
		int epos=pos+1; // skip tag
		Ref<AVector<SignedData<Block>>> blocks = Format.readRef(b,epos);
		if (blocks==null) {
			throw new BadFormatException("Null blocks in Order!");
		}
		epos+=blocks.getEncodingLength();
		
		long pp = Format.readVLCLong(b,epos);
		epos+=Format.getVLCLength(pp);
		long cp = Format.readVLCLong(b,epos);
		epos+=Format.getVLCLength(cp);
		long ts = Format.readVLCLong(b,epos); // TODO: should just be 8 bytes?
		epos+=Format.getVLCLength(ts);
		
		if (pp<cp) {
			throw new BadFormatException("Proposal point ["+pp+"] before consensus point [" + cp+"]");
		}

		
		Order result=new Order(blocks, pp, cp,ts);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}
	

	@Override public final boolean isCVMValue() {
		// Orders exist outside CVM only
		return false;
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
		return commonPrefix >= consensusPoint;
	}

	/**
	 * Gets the Consensus Point of this Order
	 * @return Consensus Point
	 */
	public long getConsensusPoint() {
		return consensusPoint;
	}
	
	/**
	 * Gets the Consensus Point of this Order for the specified level
	 * @param level Consensus level
	 * @return Consensus Point
	 */
	public long getConsensusPoint(int level) {
		switch (level) {
			case 0: return getBlockCount();
			case 1: return getProposalPoint();
			case 2: return getConsensusPoint();
			default: throw new Error("Illegal consensus level: "+level);
		}
	}

	/**
	 * Gets the Proposal Point of this Order
	 * @return Proposal Point
	 */
	public long getProposalPoint() {
		return proposalPoint;
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
		return create(newBlocks.getRef(), proposalPoint, consensusPoint, timestamp);
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
		long newProposalPoint = Math.min(nblocks, proposalPoint);
		long newConsensusPoint = Math.min(nblocks, consensusPoint);
		
		return create(newBlocks.getRef(), newProposalPoint, newConsensusPoint, timestamp);
	}
	
	/**
	 * Updates timestamp in this Order. Returns the same Order if timestamp is identical.
	 * @param newTimestamp New timestamp to use
	 * @return Updated Order, or the same Order if unchanged
	 */
	public Order withTimestamp(long newTimestamp) {
		if (timestamp == newTimestamp) return this;
		return create(blocks, proposalPoint, consensusPoint, newTimestamp);
	}

	/**
	 * Updates this Order with a new proposal position. It is an error to set the
	 * proposal point before the consensus point, or beyond the last block.
	 * 
	 * @param newProposalPoint New Proposal Point in Order 
	 * @return Updated Order 
	 */
	public Order withProposalPoint(long newProposalPoint) {
		if (this.proposalPoint == newProposalPoint) return this;
		if (newProposalPoint < consensusPoint) {
			throw new IllegalArgumentException(
					"Trying to move proposed consensus before confirmed consensus?! " + newProposalPoint);
		}
		if (newProposalPoint > getBlocks().count()) throw new IndexOutOfBoundsException("Block index: " + newProposalPoint);
		return new Order(blocks, newProposalPoint, consensusPoint, timestamp);
	}

	/**
	 * Updates this Order with a new consensus position.
	 * 
	 * Proposal point will be set to the max of the consensus point and the current
	 * proposal point
	 * 
	 * @param newConsensusPoint New consensus point
	 * @return Updated chain, or this Chain instance if no change.
	 */
	public Order withConsensusPoint(long newConsensusPoint) {
		if (this.consensusPoint == newConsensusPoint) return this;
		if (newConsensusPoint > getBlocks().count())
			throw new IndexOutOfBoundsException("Block index: " + newConsensusPoint);
		long newProposalPoint = Math.max(proposalPoint, newConsensusPoint);
		return create(blocks, newProposalPoint, newConsensusPoint, timestamp);
	}
	
	/**
	 * Updates this Order with a new consensus position.
	 * 
	 * @param level Consensus level to update
	 * @param newPosition New consensus point
	 * @return Updated chain, or this Chain instance if no change.
	 */
	public Order withConsensusPoint(int level,long newPosition) {
		switch (level) {
		case 1: return withProposalPoint(newPosition);
		case 2: return withConsensusPoint(newPosition);
		default: throw new Error("Illegal level");
		}
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
		return create(blocks, 0, 0,timestamp);
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
	public ACell get(ACell key) {
		if (Keywords.BLOCKS.equals(key)) return getBlocks();
		if (Keywords.CONSENSUS_POINT.equals(key)) return CVMLong.create(consensusPoint);
		if (Keywords.PROPOSAL_POINT.equals(key)) return CVMLong.create(proposalPoint);
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
		return new Order(newBlocks, proposalPoint, consensusPoint, timestamp);
	}

	/**
	 * Tests if this Order is equivalent to another in terms of consensus (timestamp ignored)
	 * @param b Order to compare with
	 * @return True if Orders are functionally equal, false otherwise
	 */
	public boolean consensusEquals(Order b) {
		if (b==null) return false; // definitely not equal
		if (this.proposalPoint!=b.proposalPoint) return false;
		if (this.consensusPoint!=b.consensusPoint) return false;
		if (!this.blocks.equals(b.blocks)) return false;
		return true;
	}

	@Override
	public RecordFormat getFormat() {
		return FORMAT;
	}

}
