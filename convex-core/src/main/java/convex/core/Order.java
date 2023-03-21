package convex.core;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.ARecord;
import convex.core.data.AVector;
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
	private final AVector<SignedData<Block>> blocks;

	private final long proposalPoint;
	private final long consensusPoint;
	private final long timestamp;

	private static final Keyword[] KEYS = new Keyword[] { Keywords.BLOCKS, Keywords.CONSENSUS_POINT, Keywords.PROPOSAL_POINT , Keywords.TIMESTAMP};
	private static final RecordFormat FORMAT = RecordFormat.of(KEYS);

	private Order(AVector<SignedData<Block>> blocks, long proposalPoint, long consensusPoint, long timestamp) {
		super(FORMAT);
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
	private static Order create(AVector<SignedData<Block>> blocks, long proposalPoint, long consensusPoint, long timestamp) {
		return new Order(blocks, proposalPoint, consensusPoint,timestamp);
	}

	/**
	 * Create an empty Order

	 * @return New Order instance
	 */
	public static Order create() {
		return create(Vectors.empty(), 0, 0,0);
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
		AVector<SignedData<Block>> blocks = Format.read(bb);
		if (blocks==null) {
			throw new BadFormatException("Null blocks in Order!");
		}
		long bcount=blocks.count();
		
		long pp = Format.readVLCLong(bb);
		long cp = Format.readVLCLong(bb);
		long ts = Format.readVLCLong(bb);
		
		if ((cp < 0) || (cp > bcount)) {
			throw new BadFormatException("Consensus point outside current block range: " + cp);
		}
		if (pp<cp) {
			throw new BadFormatException("Proposal point ["+pp+"] before consensus point [" + cp+"]");
		}
		if (pp>bcount) {
			throw new BadFormatException("Proposal point outside block range: " + pp);
		}
		return new Order(blocks, pp, cp,ts);
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
		long commonPrefix = blocks.commonPrefixLength(bc.blocks);
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
		return blocks;
	}

	/**
	 * Get a specific Block in this Order
	 * @param i Index of Block
	 * @return Block at specified index.
	 */
	public SignedData<Block> getBlock(long i) {
		return blocks.get(i);
	}

	/**
	 * Append a new block of transactions in this Order
	 * 
	 * @param block Block to append
	 * @return The updated chain
	 */
	public Order append(SignedData<Block> block) {
		AVector<SignedData<Block>> newBlocks = blocks.append(block);
		return create(newBlocks, proposalPoint, consensusPoint, timestamp);
	}

	/**
	 * Updates blocks in this Order. Returns the same Order if the blocks are identical.
	 * @param newBlocks New blocks to use
	 * @return Updated Order, or the same order if unchanged
	 */
	public Order withBlocks(AVector<SignedData<Block>> newBlocks) {
		if (blocks == newBlocks) return this;
		return create(newBlocks, proposalPoint, consensusPoint, timestamp);
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
		if (newProposalPoint > blocks.count()) throw new IndexOutOfBoundsException("Block index: " + newProposalPoint);
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
	public Order withConsenusPoint(long newConsensusPoint) {
		if (this.consensusPoint == newConsensusPoint) return this;
		if (newConsensusPoint > blocks.count())
			throw new IndexOutOfBoundsException("Block index: " + newConsensusPoint);
		long newProposalPoint = Math.max(proposalPoint, newConsensusPoint);
		return create(blocks, newProposalPoint, newConsensusPoint, timestamp);
	}

	/**
	 * Get the number of Blocks in this Order
	 * @return Number of Blocks
	 */
	public long getBlockCount() {
		return blocks.count();
	}

	/**
	 * Clears the consensus and proposal point
	 * @return Updated order with zeroed consensus positions
	 */
	public Order withoutConsenus() {
		return create(blocks, 0, 0,timestamp);
	}

	/**
	 * Update this chain with a new list of blocks
	 * 
	 * @param newBlocks New vector of blocks to use in this Chain
	 * @return The updated Order
	 */
	public Order updateBlocks(AVector<SignedData<Block>> newBlocks) {
		if (blocks == newBlocks) return this;
		
		// Update proposal point and consensus point if necessary to ensure consistency
		long nblocks=newBlocks.count();
		long newProposalPoint = Math.min(nblocks, proposalPoint);
		long newConsensusPoint = Math.min(consensusPoint, newProposalPoint);
		
		return create(newBlocks, newProposalPoint, newConsensusPoint, timestamp);
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
		return blocks.getRefCount();
	}

	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		return blocks.getRef(i);
	}

	@Override
	public Order updateRefs(IRefFunction func) {
		AVector<SignedData<Block>> newBlocks = blocks.updateRefs(func);
		return this.withBlocks(newBlocks);
	}
	
	@Override
	public byte getTag() {
		return Tag.ORDER;
	}

	@Override
	public ACell get(ACell key) {
		if (Keywords.BLOCKS.equals(key)) return blocks;
		if (Keywords.CONSENSUS_POINT.equals(key)) return CVMLong.create(consensusPoint);
		if (Keywords.PROPOSAL_POINT.equals(key)) return CVMLong.create(proposalPoint);
		if (Keywords.TIMESTAMP.equals(key)) return CVMLong.create(timestamp);

		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Order updateAll(ACell[] newVals) {
		AVector<SignedData<Block>> blocks = (AVector<SignedData<Block>>)newVals[0];
		long consensusPoint = ((CVMLong)newVals[1]).longValue();
		long proposalPoint = ((CVMLong)newVals[2]).longValue();
		long ts = ((CVMLong)newVals[3]).longValue();

		if (blocks == this.blocks && consensusPoint == this.consensusPoint
			&& proposalPoint == this.proposalPoint) {
			return this;
		}

		return new Order(blocks, proposalPoint, consensusPoint, ts);
	}
	
}
