package convex.core.cvm;

import convex.core.cpos.Block;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ABlob;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Immutable index from the hashes of a Peer's executed transactions and Blocks to their
 * positions in its Order. Extending it gives a new index.
 *
 * <p>Peers derived from one another share a holder for their latest index, so a lookup
 * indexes only the Blocks executed since the last one. A shared index may run ahead of
 * a Peer, which then ignores positions at or beyond its own executed Blocks.</p>
 */
final class PeerIndex {

	/** Index of no Blocks */
	static final PeerIndex EMPTY=new PeerIndex(0,Index.none(),Index.none());

	/** Number of leading Blocks indexed */
	private final long position;
	private final Index<Hash,AVector<CVMLong>> txLocations;
	private final Index<Hash,CVMLong> blockLocations;

	private PeerIndex(long position, Index<Hash,AVector<CVMLong>> txLocations, Index<Hash,CVMLong> blockLocations) {
		this.position=position;
		this.txLocations=txLocations;
		this.blockLocations=blockLocations;
	}

	/**
	 * Gets the number of leading Blocks this index covers
	 * @return Number of Blocks indexed
	 */
	long getPosition() {
		return position;
	}

	/**
	 * Tests whether this index agrees with an Order's Blocks below a limit, judged by the
	 * last Block both cover. Blocks below a Peer's finality point never change, so this
	 * fails only for an index from another history.
	 *
	 * @param blocks Blocks of the Order
	 * @param limit Number of leading Blocks to compare
	 * @return True if the index can serve lookups for these Blocks
	 */
	boolean agrees(AVector<SignedData<Block>> blocks, long limit) {
		long last=Math.min(position,limit)-1;
		if (last<0) return true;
		CVMLong indexed=blockLocations.get(blocks.get(last).getHash());
		return (indexed!=null)&&(indexed.longValue()==last);
	}

	/**
	 * Extends this index to cover the Blocks below a limit
	 * @param blocks Blocks of the Order, agreeing with this index
	 * @param limit Number of leading Blocks to cover, at least the current position
	 * @return Extended index
	 */
	PeerIndex extend(AVector<SignedData<Block>> blocks, long limit) {
		Index<Hash,AVector<CVMLong>> txLocs=txLocations;
		Index<Hash,CVMLong> blockLocs=blockLocations;
		for (long b=position; b<limit; b++) {
			SignedData<Block> signedBlock=blocks.get(b);
			blockLocs=blockLocs.assoc(signedBlock.getHash(),CVMLong.create(b));
			AVector<SignedData<ATransaction>> txs=RT.ensureCVM(signedBlock.getValue(),Block.class).getTransactions();
			long n=txs.count();
			for (long i=0; i<n; i++) {
				txLocs=txLocs.assoc(txs.get(i).getHash(),Vectors.createLongs(b,i));
			}
		}
		return new PeerIndex(limit,txLocs,blockLocs);
	}

	/**
	 * Gets the location of a transaction in the Blocks below a limit
	 * @param txID Hash of the signed transaction
	 * @param limit Number of leading Blocks to search
	 * @return Vector of Block position and transaction position, or null if not found
	 */
	AVector<CVMLong> getTransactionLocation(ABlob txID, long limit) {
		AVector<CVMLong> loc=txLocations.get(txID);
		if ((loc==null)||(loc.get(0).longValue()>=limit)) return null;
		return loc;
	}

	/**
	 * Gets the position of a Block below a limit
	 * @param blockHash Hash of the signed Block
	 * @param limit Number of leading Blocks to search
	 * @return Block position, or null if not found
	 */
	CVMLong getBlockIndex(Hash blockHash, long limit) {
		CVMLong pos=blockLocations.get(blockHash);
		if ((pos==null)||(pos.longValue()>=limit)) return null;
		return pos;
	}
}
