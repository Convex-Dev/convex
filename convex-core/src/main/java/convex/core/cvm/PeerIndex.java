package convex.core.cvm;

import convex.core.Result;
import convex.core.cpos.Block;
import convex.core.cpos.BlockResult;
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
 *
 * <p>The same signed transaction may be included in several Blocks, for example when its
 * client sends it to more than one peer. At most one copy runs: running uses up its
 * sequence number, so every other copy is rejected. A lookup reports the copy that ran,
 * whether it succeeded or failed, and otherwise the first copy.</p>
 */
final class PeerIndex {

	/** Index of no Blocks */
	static final PeerIndex EMPTY=new PeerIndex(0,Index.none(),Index.none(),Index.none());

	/** Number of leading Blocks indexed */
	private final long position;

	/** Location of the first copy of each transaction */
	private final Index<Hash,AVector<CVMLong>> txLocations;

	/** Locations of every copy, in Block order, for transactions included more than once */
	private final Index<Hash,AVector<AVector<CVMLong>>> repeatLocations;

	private final Index<Hash,CVMLong> blockLocations;

	private PeerIndex(long position, Index<Hash,AVector<CVMLong>> txLocations,
			Index<Hash,AVector<AVector<CVMLong>>> repeatLocations, Index<Hash,CVMLong> blockLocations) {
		this.position=position;
		this.txLocations=txLocations;
		this.repeatLocations=repeatLocations;
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
		Index<Hash,AVector<AVector<CVMLong>>> repeatLocs=repeatLocations;
		Index<Hash,CVMLong> blockLocs=blockLocations;
		for (long b=position; b<limit; b++) {
			SignedData<Block> signedBlock=blocks.get(b);
			blockLocs=blockLocs.assoc(signedBlock.getHash(),CVMLong.create(b));
			AVector<SignedData<ATransaction>> txs=RT.ensureCVM(signedBlock.getValue(),Block.class).getTransactions();
			long n=txs.count();
			for (long i=0; i<n; i++) {
				Hash txID=txs.get(i).getHash();
				AVector<CVMLong> loc=Vectors.createLongs(b,i);
				AVector<CVMLong> first=txLocs.get(txID);
				if (first==null) {
					txLocs=txLocs.assoc(txID,loc);
					continue;
				}
				AVector<AVector<CVMLong>> copies=repeatLocs.get(txID);
				if (copies==null) copies=Vectors.of(first);
				repeatLocs=repeatLocs.assoc(txID,copies.conj(loc));
			}
		}
		return new PeerIndex(limit,txLocs,repeatLocs,blockLocs);
	}

	/**
	 * Gets the location of a transaction in the Blocks below a limit. For a transaction
	 * included more than once, this is the copy that ran, or the first copy if none did.
	 *
	 * @param peer Peer whose Block results show which copy ran
	 * @param txID Hash of the signed transaction
	 * @param limit Number of leading Blocks to search
	 * @return Vector of Block position and transaction position, or null if not found
	 */
	AVector<CVMLong> getTransactionLocation(Peer peer, ABlob txID, long limit) {
		AVector<CVMLong> first=txLocations.get(txID);
		if ((first==null)||(first.get(0).longValue()>=limit)) return null;
		AVector<AVector<CVMLong>> copies=repeatLocations.get(txID);
		if (copies==null) return first;
		long n=copies.count();
		for (long j=0; j<n; j++) {
			AVector<CVMLong> loc=copies.get(j);
			if (loc.get(0).longValue()>=limit) break;
			if (ran(peer,loc)) return loc;
		}
		return first;
	}

	/**
	 * Tests whether the transaction at a location ran. A copy that ran was charged fees,
	 * whether it succeeded or failed; a copy rejected before running never is.
	 */
	private static boolean ran(Peer peer, AVector<CVMLong> loc) {
		BlockResult br=peer.getBlockResult(loc.get(0).longValue());
		if (br==null) return false;
		Result r=br.getResult(loc.get(1).longValue());
		if ((r==null)||(r.getInfo()==null)) return false;
		CVMLong fees=RT.ensureLong(r.getInfo().get(Keywords.FEES));
		return (fees!=null)&&(fees.longValue()>0);
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
