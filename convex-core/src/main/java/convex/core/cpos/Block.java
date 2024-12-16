package convex.core.cpos;

import java.util.Comparator;
import java.util.List;

import convex.core.Constants;
import convex.core.cvm.ARecordGeneric;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Keywords;
import convex.core.cvm.RecordFormat;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.ErrorMessages;
import convex.core.util.Utils;

/**
 * A Block contains an ordered collection of signed transactions that may be applied 
 * collectively as part of a state update.
 * 
 * Blocks represent the units of novelty in the consensus system: a future state is 
 * 100% deterministic given the previous state and the Block to be applied.
 * 
 * "Man, the living creature, the creating individual, is always more important
 * than any established style or system." - Bruce Lee
 *
 */
public final class Block extends ARecordGeneric {

	private static final Keyword[] BLOCK_KEYS = new Keyword[] { Keywords.TIMESTAMP, Keywords.TRANSACTIONS};
	private static final RecordFormat FORMAT = RecordFormat.of(BLOCK_KEYS);

	private final long timestamp;
	private AVector<SignedData<ATransaction>> transactions;
	
	private static final int IX_TIMESTAMP= 0;
	private static final int IX_TRANSACTIONS = 1;

	private static final long NUM_FIELDS = FORMAT.count();

	/**
	 * Comparator to sort blocks by timestamp
	 */
	static final Comparator<SignedData<Block>> TIMESTAMP_COMPARATOR = new Comparator<>() {
		@Override
		public int compare(SignedData<Block> a, SignedData<Block> b) {
			Block ba=a.getValue();
			Block bb=b.getValue();
			
			int sig = Long.compare(ba.getTimeStamp(), bb.getTimeStamp());
			return sig;
		}
	};

	private Block(long timestamp, AVector<SignedData<ATransaction>> transactions) {
		super(CVMTag.BLOCK,FORMAT,Vectors.create(CVMLong.create(timestamp),transactions));
		this.timestamp = timestamp;
		this.transactions = transactions;
	}

	private Block(AVector<ACell> values) {
		super(CVMTag.BLOCK,FORMAT,values);
		this.timestamp=RT.ensureLong(values.get(IX_TIMESTAMP)).longValue();
		
	}

	@Override
	public ACell get(Keyword k) {
		if (Keywords.TIMESTAMP.equals(k)) return values.get(IX_TIMESTAMP);
		if (Keywords.TRANSACTIONS.equals(k)) return getTransactions();
		return null;
	}

	/**
	 * Gets the timestamp of this block
	 * 
	 * @return Timestamp, as a long value
	 */
	public long getTimeStamp() {
		return timestamp;
	}

	/**
	 * Creates a block with the given timestamp and transactions
	 * 
	 * @param timestamp Timestamp for the newly created Block.
	 * @param transactions A java.util.List instance containing the required transactions
	 * @return A new Block containing the specified signed transactions
	 */
	public static Block create(long timestamp, List<SignedData<ATransaction>> transactions) {
		return new Block(timestamp, Vectors.create(transactions));
	}

	/**
	 * Creates a block with the given transactions.
	 * 
	 * @param timestamp Timestamp of block creation, according to Peer
	 * @param transactions Vector of transactions to include in Block
	 * 
	 * @return A new Block containing the specified signed transactions
	 */
	public static Block create(long timestamp, AVector<SignedData<ATransaction>> transactions) {
		return new Block(timestamp, transactions);
	}

	/**
	 * Creates a block with the given transactions.
	 * 
	 * @param timestamp Timestamp of block creation, according to Peer
	 * @param transactions Array of transactions to include in Block
	 * @return New Block
	 */
	@SafeVarargs
	public static Block of(long timestamp, SignedData<ATransaction>... transactions) {
		return new Block(timestamp, Vectors.create(transactions));
	}

	/**
	 * Gets the length of this block in number of transactions
	 * 
	 * @return Number of transactions on this block
	 */
	public int length() {
		return Utils.checkedInt(getTransactions().count());
	}

	
	@Override
	public int estimatedEncodingSize() {
		// allow for embedded transaction, timestamp always small
		return 160;
	}
	
	/**
	 * Read a Block from a Blob encoding
	 * @throws BadFormatException In event of encoding error
	 */
	public static Block read(Blob b, int pos) throws BadFormatException {
		AVector<ACell> values=Vectors.read(b, pos);
		int epos=pos+values.getEncodingLength();

		if (values.count()!=BLOCK_KEYS.length) throw new BadFormatException(ErrorMessages.RECORD_VALUE_NUMBER);

		Block result=new Block(values);
		result.attachEncoding(b.slice(pos,epos));
		return result;
	}

	/**
	 * Get the vector of transactions in this Block
	 * @return Vector of transactions
	 */
	public AVector<SignedData<ATransaction>> getTransactions() {
		if (transactions==null) transactions=RT.ensureVector(values.get(IX_TRANSACTIONS));
		return transactions;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (values.count()!=NUM_FIELDS) throw new InvalidDataException("Wrong field count",this);
	}
	
	@Override
	public void validateStructure() throws InvalidDataException {
		AVector<SignedData<ATransaction>> txs=getTransactions();
		if (txs==null) throw new InvalidDataException("No transactions",this);
		if (txs.count()>Constants.MAX_TRANSACTIONS_PER_BLOCK) {
			throw new InvalidDataException("Too many transactions: "+txs.count(),this);
		}
		// We don't validate individual transactions here
		// This gets enforced latter when transactions are applied
	}
	
	@Override 
	public boolean equals(ACell a) {
		if (a instanceof Block) return equals((Block)a);
		return Cells.equalsGeneric(this, a);
	}
	
	/**
	 * Tests if this Block is equal to another
	 * @param a PeerStatus to compare with
	 * @return true if equal, false otherwise
	 */
	public boolean equals(Block a) {
		if (this==a) return true;
		if (a == null) return false;
		if (timestamp!=a.timestamp) return false;
		
		Hash h=this.cachedHash();
		if (h!=null) {
			Hash ha=a.cachedHash();
			if (ha!=null) return Cells.equals(h, ha);
		}
		
		if (!(Cells.equals(values, a.values))) return false;
		return true;
	}

	@Override
	protected ARecordGeneric withValues(AVector<ACell> newValues) {
		if (values==newValues) return this;
		return new Block(values);
	}	
}
