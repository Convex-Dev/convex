package convex.core;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;

/**
 * Class representing an ordering of transactions, along with the consensus position.
 * 
 * An ordering contains: 
 * <ul>
 * <li>The vector of known verified blocks announced by the Peer</li>
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
	private final AVector<Block> blocks;

	private final long proposalPoint;
	private final long consensusPoint;

	private Order(AVector<Block> blocks, long proposalPoint, long consensusPoint) {
		this.blocks = blocks;
		this.consensusPoint = consensusPoint;
		this.proposalPoint = proposalPoint;
	}

	private static Order create(AVector<Block> blocks, long proposalPoint, long consensusPoint) {
		return new Order(blocks, proposalPoint, consensusPoint);
	}

	public static Order create() {
		return create(Vectors.empty(), 0, 0);
	}

	@Override
	public ByteBuffer write(ByteBuffer bb) {
		bb = bb.put(Tag.ORDER);
		return writeRaw(bb);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		bb = blocks.write(bb);
		bb = Format.writeVLCLong(bb, proposalPoint);
		bb = Format.writeVLCLong(bb, consensusPoint);
		return bb;
	}

	public static Order read(ByteBuffer bb) throws BadFormatException {
		AVector<Block> blocks = Format.read(bb);
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
	public int estimatedEncodingSize() {
		return 100;
	}

	@Override
	public boolean isCanonical() {
		// Always canonical?
		return true;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#order {");
		sb.append(":prop " + getProposalPoint() + ",");
		sb.append(":cons " + getConsensusPoint() + ",");
		sb.append(":hash " + getHash() + ",");
		sb.append(":blocks ");
		blocks.ednString(sb);
		sb.append("}\n");
	}

	/**
	 * Checks if an Order is consistent with this Order.
	 * 
	 * Order is defined as consistent iff: 
	 * <ul>
	 * <li> Blocks are equal up to the consensus
	 * point of this Order
	 * </li>
	 * </ul>
	 * 
	 * @param bc
	 * @return True if chains are consistent, false otherwise.
	 */
	public boolean isConsistent(Order bc) {
		if (blocks.equals(bc.blocks)) return true;
		long commonPrefix = blocks.commonPrefixLength(bc.blocks);
		return commonPrefix >= consensusPoint;
	}

	public long getConsensusPoint() {
		return consensusPoint;
	}

	public long getProposalPoint() {
		return proposalPoint;
	}

	public AVector<Block> getBlocks() {
		return blocks;
	}

	public Block getBlock(long i) {
		return blocks.get(i);
	}

	/**
	 * Propose a new block of transactions in this Order
	 * 
	 * @param block
	 * @return The updated chain
	 */
	public Order propose(Block block) {
		AVector<Block> newBlocks = blocks.append(block);
		return create(newBlocks, proposalPoint, consensusPoint);
	}

	/**
	 * Updates blocks in this Order. Returns the same order if the blocks are identical.
	 * @param newBlocks
	 * @return Updated Order, or the same order if unchanged
	 */
	public Order withBlocks(AVector<Block> newBlocks) {
		if (blocks == newBlocks) return this;
		return create(newBlocks, proposalPoint, consensusPoint);
	}

	/**
	 * Updates this Order with a new proposal position. It is an error to set the
	 * proposal point before the consensus point, or beyond the last block.
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
	 * @param newConsensusPoint
	 * @return Updated chain, or this Chain instance if no change.
	 */
	public Order withConsenusPoint(long newConsensusPoint) {
		if (this.consensusPoint == newConsensusPoint) return this;
		if (newConsensusPoint > blocks.count())
			throw new IndexOutOfBoundsException("Block index: " + newConsensusPoint);
		long newProposalPoint = Math.max(proposalPoint, newConsensusPoint);
		return create(blocks, newProposalPoint, newConsensusPoint);
	}

	public long getBlockCount() {
		return blocks.count();
	}

	public Order withoutConsenus() {
		return create(blocks, 0, 0);
	}

	/**
	 * Update this chain with a new list of blocks
	 * 
	 * @param newBlocks New vector of blocks to use in this Chain
	 * @return The updated Order
	 */
	public Order updateBlocks(AVector<Block> newBlocks) {
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
	public <R> Ref<R> getRef(int i) {
		return blocks.getRef(i);
	}

	@Override
	public Order updateRefs(IRefFunction func) {
		AVector<Block> newBlocks = blocks.updateRefs(func);
		return this.withBlocks(newBlocks);
	}

	@Override
	protected boolean isEmbedded() {
		// Order is a potentially huge data structure, don't embed
		return false;
	}

}
