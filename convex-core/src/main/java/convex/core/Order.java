package convex.core;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.BlobBuilder;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Tag;
import convex.core.data.Vectors;
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
public class Order extends ACell {
	private final AVector<SignedData<Block>> blocks;

	private final long proposalPoint;
	private final long consensusPoint;

	private Order(AVector<SignedData<Block>> blocks, long proposalPoint, long consensusPoint) {
		this.blocks = blocks;
		this.consensusPoint = consensusPoint;
		this.proposalPoint = proposalPoint;
	}

	/**
	 * Create an Order
	 * @param blocks Blocks in Order
	 * @param proposalPoint Proposal Point
	 * @param consensusPoint Consensus Point
	 * @return New Order instance
	 */
	private static Order create(AVector<SignedData<Block>> blocks, long proposalPoint, long consensusPoint) {
		return new Order(blocks, proposalPoint, consensusPoint);
	}

	/**
	 * Create an empty Order

	 * @return New Order instance
	 */
	public static Order create() {
		return create(Vectors.empty(), 0, 0);
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
		
		if ((cp < 0) || (cp > bcount)) {
			throw new BadFormatException("Consensus point outside current block range: " + cp);
		}
		if (pp<cp) {
			throw new BadFormatException("Proposal point ["+pp+"] before consensus point [" + cp+"]");
		}
		if (pp>bcount) {
			throw new BadFormatException("Proposal point outside block range: " + pp);
		}
		return new Order(blocks, pp, cp);
	}



	@Override
	public boolean isCanonical() {
		// Always canonical?
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		// Orders exist outside CVM only
		return false;
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		sb.append("{");
		sb.append(":prop " + getProposalPoint() + ",");
		sb.append(":cons " + getConsensusPoint() + ",");
		sb.append(":hash " + getHash() + ",");
		sb.append(":blocks ");
		if (!blocks.print(sb,limit)) return false;
		sb.append("}\n");
		return sb.check(limit);
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
		return create(newBlocks, proposalPoint, consensusPoint);
	}

	/**
	 * Updates blocks in this Order. Returns the same Order if the blocks are identical.
	 * @param newBlocks New blocks to use
	 * @return Updated Order, or the same order if unchanged
	 */
	public Order withBlocks(AVector<SignedData<Block>> newBlocks) {
		if (blocks == newBlocks) return this;
		return create(newBlocks, proposalPoint, consensusPoint);
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
		return new Order(blocks, newProposalPoint, consensusPoint);
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
		return create(blocks, newProposalPoint, newConsensusPoint);
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
		return create(blocks, 0, 0);
	}

	/**
	 * Update this chain with a new list of blocks
	 * 
	 * @param newBlocks New vector of blocks to use in this Chain
	 * @return The updated Order
	 */
	public Order updateBlocks(AVector<SignedData<Block>> newBlocks) {
		if (blocks == newBlocks) return this;
		long prefix = blocks.commonPrefixLength(newBlocks);
		long newProposalPoint = Math.min(prefix, proposalPoint);
		long newConsensusPoint = Math.min(consensusPoint, newProposalPoint);
		return create(newBlocks, newProposalPoint, newConsensusPoint);
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
	public ACell toCanonical() {
		return this;
	}
}
