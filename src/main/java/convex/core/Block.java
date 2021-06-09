package convex.core;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.List;

import convex.core.data.ACell;
import convex.core.data.ARecord;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.SignedData;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.lang.impl.RecordFormat;
import convex.core.transactions.ATransaction;
import convex.core.util.Utils;

/**
 * A block contains an ordered collection of signed transactions that may be applied 
 * collectively as part of a state update.
 * 
 * Blocks represent the units of novelty in the consensus system: a future state is 
 * 100% deterministic given the previous state and the Block to be applied.
 * 
 * "Man, the living creature, the creating individual, is always more important
 * than any established style or system." - Bruce Lee
 *
 */
public class Block extends ARecord {
	private final long timestamp;
	private final AVector<SignedData<ATransaction>> transactions;
	private final AccountKey peerKey;

	private static final Keyword[] BLOCK_KEYS = new Keyword[] { Keywords.TIMESTAMP, Keywords.TRANSACTIONS, Keywords.PEER };
	private static final RecordFormat FORMAT = RecordFormat.of(BLOCK_KEYS);

	public static final Comparator<Block> TIMESTAMP_COMPARATOR = new Comparator<>() {
		@Override
		public int compare(Block a, Block b) {
			int sig = Long.compare(a.getTimeStamp(), b.getTimeStamp());
			return sig;
		}
	};

	private Block(long timestamp, AVector<SignedData<ATransaction>> transactions, AccountKey peer) {
		super(FORMAT);
		this.timestamp = timestamp;
		this.transactions = transactions;
		this.peerKey=peer;
		
		if (peerKey==null) throw new Error("Trying to construct block with null peer key");
	}

	@Override
	public ACell get(ACell k) {
		if (Keywords.TIMESTAMP.equals(k)) return CVMLong.create(timestamp);
		if (Keywords.TRANSACTIONS.equals(k)) return transactions;
		if (Keywords.PEER.equals(k)) return peerKey;
		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected Block updateAll(ACell[] newVals) {
		long newTimestamp = RT.ensureLong(newVals[0]).longValue();		
		AVector<SignedData<ATransaction>> newTransactions = (AVector<SignedData<ATransaction>>) newVals[1];
		AccountKey newPeer = (AccountKey) newVals[2];
		if ((this.transactions == newTransactions) && (this.timestamp == newTimestamp) && (peerKey==newPeer)) {
			return this;
		}
		return new Block(newTimestamp, newTransactions,newPeer);
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
	 * Gets the Peer for this block
	 * 
	 * @return Address of Peer publishing this block
	 */
	public AccountKey getPeer() {
		return peerKey;
	}

	/**
	 * Creates a block with the given timestamp and transactions
	 * 
	 * @param timestamp Timestamp for the newly created Block.
	 * @param transactions A java.util.List instance containing the required transactions
	 * @param peerAddress 
	 * @return A new Block containing the specified signed transactions
	 */
	public static Block create(long timestamp, List<SignedData<ATransaction>> transactions, AccountKey peerAddress) {
		return new Block(timestamp, Vectors.create(transactions),peerAddress);
	}

	/**
	 * Creates a block with the given transactions
	 * 
	 * @param timestamp Timestamp of block creation, according to Peer
	 * @param peerKey Public key of Peer producing Block
	 * @param transactions Vector of transactions to include in Block
	 * 
	 * @return A new Block containing the specified signed transactions
	 */
	public static Block create(long timestamp, AccountKey peerKey, AVector<SignedData<ATransaction>> transactions) {
		return new Block(timestamp, transactions,peerKey);
	}

	@SafeVarargs
	public static Block of(long timestamp, AccountKey peerKey, SignedData<ATransaction>... transactions) {
		return new Block(timestamp, Vectors.of((Object[])transactions),peerKey);
	}

	/**
	 * Gets the length of this block.
	 * 
	 * @return Number of transactions on this block
	 */
	public int length() {
		return Utils.checkedInt(transactions.count());
	}
	
	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=getTag();
		// generic record writeRaw, handles all fields in declared order
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Utils.writeLong(bs,pos, timestamp);
		pos = transactions.write(bs,pos);
		pos = peerKey.writeToBuffer(bs, pos);
		return pos;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 10+transactions.estimatedEncodingSize()+AccountKey.LENGTH;
	}

	/**
	 * Reads a Block from the given bytebuffer, assuming tag is already read
	 * 
	 * @param bb ByteBuffer containing Block representation
	 * @return A Block
	 * @throws BadFormatException if a Block could noy be read.
	 */
	public static Block read(ByteBuffer bb) throws BadFormatException {
		long timestamp = Format.readLong(bb);
		try {
			AVector<SignedData<ATransaction>> transactions = Format.read(bb);
			if (transactions==null) throw new BadFormatException("Null transactions");
			
			AccountKey peer=AccountKey.readRaw(bb);
			if (peer==null) throw new BadFormatException("Bad peer key in Block");
			return Block.create(timestamp, peer,transactions);
		} catch (ClassCastException e) {
			throw new BadFormatException("Error reading Block format", e);
		}
	}



	public AVector<SignedData<ATransaction>> getTransactions() {
		return transactions;
	}

	@Override
	public boolean isCanonical() {
		if (!transactions.isCanonical()) return false;
		return true;
	}

	@Override
	public byte getTag() {
		return Tag.BLOCK;
	}

	@Override
	protected String ednTag() {
		return "#block";
	}

	@Override
	public void validateCell() throws InvalidDataException {
		transactions.validateCell();
	}
	
}
