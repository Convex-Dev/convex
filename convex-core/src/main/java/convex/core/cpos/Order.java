package convex.core.cpos;

import convex.core.cvm.ARecordGeneric;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Keywords;
import convex.core.cvm.RecordFormat;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;

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
public class Order extends ARecordGeneric {
	private static final Keyword[] KEYS = new Keyword[] { Keywords.TIMESTAMP,Keywords.CONSENSUS, Keywords.BLOCKS};
	private static final RecordFormat FORMAT = RecordFormat.of(KEYS);
	private static final int IX_TIMESTAMP = 0;
	private static final int IX_CONSENSUS = 1;
	private static final int IX_BLOCKS = 2;
	
	private static final long NUM_FIELDS=FORMAT.count();

	/**
	 * Timestamp of this Order, i.e. the timestamp of the peer at the time it was created
	 */
	private final long timestamp;
	
	/**
	 * Array of consensus points for each consensus level. The first element (block count)
	 * is ignored.
	 */
	private final long [] consensusPoints;
	
	private static final long[] EMPTY_CONSENSUS_ARRAY = new long[CPoSConstants.CONSENSUS_LEVELS];

	@SuppressWarnings("unchecked")
	private Order(AVector<ACell> values) {
		super(CVMTag.ORDER,FORMAT,values);
		this.timestamp = RT.ensureLong(values.get(IX_TIMESTAMP)).longValue();
		this.consensusPoints = RT.toLongArray((AVector<ACell>)values.get(IX_CONSENSUS));
	}
	
	private Order(long timestamp, long[] consensusPoints, AVector<SignedData<Block>> blocks) {
		super(CVMTag.ORDER,FORMAT,Vectors.create(CVMLong.create(timestamp),Vectors.createLongs(consensusPoints),blocks));
		this.timestamp = timestamp;
		this.consensusPoints=consensusPoints;
	}

	/**
	 * Create an Order
	 * @param blocks Blocks in Order
	 * @param proposalPoint Proposal Point
	 * @param consensusPoint Consensus Point
	 * @return New Order instance
	 */
	private static Order create(AVector<SignedData<Block>> blocks, long proposalPoint, long consensusPoint, long timestamp) {
		long[] consensusPoints=new long[CPoSConstants.CONSENSUS_LEVELS];
		consensusPoints[0] = blocks.count();
		consensusPoints[1] = proposalPoint;
		consensusPoints[2] = consensusPoint;

		return new Order(timestamp, consensusPoints,blocks);
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
		return create(Vectors.create(blocks), proposalPoint, consensusPoint,0);
	}
	
	/**
	 * Create an empty Order

	 * @return New Order instance
	 */
	public static Order create() {
		return new Order(0, EMPTY_CONSENSUS_ARRAY,Vectors.empty());
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
		AVector<ACell> values = Vectors.read(b, pos);
		if (values.count()!=NUM_FIELDS) throw new BadFormatException("Wrong number of Order fields");
		long epos=pos+values.getEncodingLength();
		
		Order result=new Order(values);
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
		return consensusPoints[level];
	}
	
	public long[] getConsensusPoints() {
		long[] result=consensusPoints.clone();
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
	@SuppressWarnings("unchecked")
	public AVector<SignedData<Block>> getBlocks() {
		return (AVector<SignedData<Block>>) values.get(IX_BLOCKS);
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
	 * Constrains consensus points as required to remain valid
	 * @param newBlocks New blocks to use
	 * @return Updated Order, or the same order if unchanged
	 */
	public Order withBlocks(AVector<SignedData<Block>> newBlocks) {
		if (getBlocks() == newBlocks) return this;
		
		// Update proposal point and consensus point if necessary to ensure consistency
		long nblocks=newBlocks.count();
		
		AVector<ACell> newValues=values.assoc(IX_BLOCKS, newBlocks);
		
		// update consensus points if required
		int n=consensusPoints.length;
		if ((nblocks!=consensusPoints[0])||(nblocks<consensusPoints[n-1])) {
			long[] nc=consensusPoints.clone();
			nc[0]=nblocks;
			for (int i=1; i<n; i++) {
				nc[i]=Math.min(nc[i],nc[i-1]);
			}
			newValues=newValues.assoc(IX_CONSENSUS, Vectors.createLongs(nc));
		}
		
		return new Order(newValues);
	}
	
	/**
	 * Updates timestamp in this Order. Returns the same Order if timestamp is identical.
	 * @param newTimestamp New timestamp to use
	 * @return Updated Order, or the same Order if unchanged
	 */
	public Order withTimestamp(long newTimestamp) {
		if (timestamp == newTimestamp) return this;
		return new Order(values.assoc(IX_TIMESTAMP, CVMLong.create(newTimestamp)));
	}
	
	/**
	 * Updates this Order with a new consensus position.
	 * 
	 * @param level Consensus level to update
	 * @param newPosition New consensus point
	 * @return Updated Order, or this Order instance if no change.
	 */
	public Order withConsensusPoint(int level,long newPosition) {
		if (consensusPoints[level]==newPosition) return this;
		long[] cps=consensusPoints.clone();
		cps[level]=newPosition;
		switch (level) {
			case 0: throw new IllegalArgumentException("Can't change number of blocks");
			default: if (cps[level-1]<newPosition) {
				throw new IllegalArgumentException("Can't set consensus level byond previous level");
			}
		}
		return new Order(values.assoc(IX_CONSENSUS,Vectors.createLongs(cps)));
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
		return new Order(values.assoc(IX_CONSENSUS,Vectors.createLongs(cps)));
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
		long[] nc=new long[CPoSConstants.CONSENSUS_LEVELS];
		nc[0]=getBlocks().count();
		return new Order(values.assoc(IX_CONSENSUS, Vectors.createLongs(nc)));
	}

	@Override
	public void validateCell() throws InvalidDataException {
		super.validateCell();
		if (!values.getRef(IX_CONSENSUS).isEmbedded()) {
			throw new InvalidDataException("Consensus values should be embedded",this);
		}

	}
	
	@Override
	public void validateStructure() throws InvalidDataException {
		super.validateStructure();
		long [] cps=getConsensusPoints();
		if (cps[0]!=getBlockCount()) {
			throw new InvalidDataException("Mimatch of block count with conesnsus points",this);
		}
		int n=cps.length;
		if (cps[n-1]<0) {
			throw new InvalidDataException("Negative final consensus point",this);
		}
		for (int i=1; i<n; i++) {
			if (cps[i]>cps[i-1]) {
				throw new InvalidDataException("Consensus points not in expected order: "+cps,this);
			}
		}
	}

	@Override
	public ACell get(Keyword key) {
		if (Keywords.TIMESTAMP.equals(key)) return values.get(IX_TIMESTAMP);
		if (Keywords.CONSENSUS.equals(key)) return values.get(IX_CONSENSUS);
		if (Keywords.BLOCKS.equals(key)) return getBlocks();
		return null;
	}

	/**
	 * Tests if this Order is equivalent to another in terms of consensus (timestamp ignored)
	 * @param b Order to compare with
	 * @return True if Orders are functionally equal, false otherwise
	 */
	public boolean consensusEquals(Order b) {
		if (b==null) return false; // definitely not equal
		for (int i=0; i<CPoSConstants.CONSENSUS_LEVELS; i++) {
			if (this.getConsensusPoint(i)!=b.getConsensusPoint(i)) return false;			
		}
		if (!this.getBlocks().equals(b.getBlocks())) return false;
		return true;
	}

	@Override
	public boolean equals(ACell o) {
		if (o instanceof Order) return equals((Order)o);
		return Cells.equalsGeneric(this, o);
	}
	
	public boolean equals(Order o) {
		if (o==null) return false;
		return values.equals(o.values);
	}


	@Override
	protected ARecordGeneric withValues(AVector<ACell> newValues) {
		return new Order(values);
	}
}
