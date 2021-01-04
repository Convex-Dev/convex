package convex.core;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.List;

import convex.core.data.ARecord;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.SignedData;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
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
	private final Address peerAddress;

	private static final Keyword[] BLOCK_KEYS = new Keyword[] { Keywords.TIMESTAMP, Keywords.TRANSACTIONS, Keywords.PEER };
	private static final RecordFormat FORMAT = RecordFormat.of(BLOCK_KEYS);

	public static final Comparator<Block> TIMESTAMP_COMPARATOR = new Comparator<>() {
		@Override
		public int compare(Block a, Block b) {
			int sig = Long.compare(a.getTimeStamp(), b.getTimeStamp());
			return sig;
		}
	};

	private Block(long timestamp, AVector<SignedData<ATransaction>> transactions, Address peer) {
		super(FORMAT);
		this.timestamp = timestamp;
		this.transactions = transactions;
		this.peerAddress=peer;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <V> V get(Keyword k) {
		if (Keywords.TIMESTAMP.equals(k)) return (V) ((Long) timestamp);
		if (Keywords.TRANSACTIONS.equals(k)) return (V) transactions;
		if (Keywords.PEER.equals(k)) return (V) peerAddress;
		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected Block updateAll(Object[] newVals) {
		long newTimestamp = (Long) newVals[0];		
		AVector<SignedData<ATransaction>> newTransactions = (AVector<SignedData<ATransaction>>) newVals[1];
		Address newPeer = (Address) newVals[2];
		if ((this.transactions == newTransactions) && (this.timestamp == newTimestamp) && (peerAddress==newPeer)) {
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
	public Address getPeer() {
		return peerAddress;
	}

	/**
	 * Creates a block with the given timestamp and transactions
	 * 
	 * @param timestamp Timestamp for the newly created Block.
	 * @param transactions A java.util.List instance containing the required transactions
	 * @param peerAddress 
	 * @return A new Block containing the specified signed transactions
	 */
	public static Block create(long timestamp, List<SignedData<ATransaction>> transactions, Address peerAddress) {
		return new Block(timestamp, Vectors.create(transactions),peerAddress);
	}

	/**
	 * Creates a block with the given transactions
	 * 
	 * @param transactions
	 * @return A new Block containing the specified signed transactions
	 */
	public static Block create(long timestamp, AVector<SignedData<ATransaction>> transactions, Address peer) {
		return new Block(timestamp, transactions,peer);
	}

	@SafeVarargs
	public static Block of(long timestamp, SignedData<ATransaction>... transactions) {
		return new Block(timestamp, Vectors.of(transactions),null);
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
		bs[pos++]=getRecordTag();
		// generic record writeRaw, handles all fields in declared order
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Utils.writeLong(bs,pos, timestamp);
		pos = transactions.write(bs,pos);
		pos = Format.write(bs,pos,peerAddress);
		return pos;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 10+transactions.estimatedEncodingSize()+Address.LENGTH;
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
			Address peer=Format.read(bb);
			return Block.create(timestamp, transactions,peer);
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
	public byte getRecordTag() {
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
