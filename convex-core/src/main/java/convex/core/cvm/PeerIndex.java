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

public class PeerIndex {

	private long finalityPoint;
	private Index<Hash,AVector<CVMLong>> txLocations;
	private Index<Hash,CVMLong> blockLocations;
	
	public PeerIndex() {
		this(0,Index.none(),Index.none());
	}

	public PeerIndex(long finalPoint, Index<Hash, AVector<CVMLong>> txLocs, Index<Hash,CVMLong> blockLocs) {
		this.finalityPoint=finalPoint;
		this.txLocations=txLocs;
		this.blockLocations=blockLocs;
	}

	public long getFinalityPoint() {
		return finalityPoint;
	}

	public PeerIndex update(Peer peer) {
		long pfp=peer.getFinalityPoint();
		if (pfp<finalityPoint) return null; // going backwards!
		if (pfp==finalityPoint) return this; // no update
		
		PeerIndex result=this;
		for (long i=finalityPoint; i<pfp; i++) {
			result=result.processBlock(peer,i);
		}
		return result;
	}

	private PeerIndex processBlock(Peer peer, long blockNum) {
		Index<Hash,AVector<CVMLong>> ntxLocs=txLocations;
		Index<Hash,CVMLong> nBlockLocs=blockLocations;
		
		BlockResult br=peer.getBlockResult(blockNum);
		SignedData<Block> blockSD=peer.getPeerOrder().getBlock(blockNum);
		nBlockLocs=nBlockLocs.assoc(blockSD.getHash(), CVMLong.create(blockNum));
		AVector<SignedData<ATransaction>> txs = blockSD.getValue().getTransactions();
		AVector<Result> rs = br.getResults();
		long n=rs.count();
		for (long i=0; i<n; i++) {
			SignedData<ATransaction> tx=txs.get(i);
			Hash txID=tx.getHash();
			ntxLocs=ntxLocs.assoc(txID,Vectors.createLongs(blockNum,i));
		}
		
		return new PeerIndex(blockNum+1,ntxLocs,nBlockLocs);
	}

	public Result getTransactionResult(Peer peer,ABlob txID) {
		AVector<CVMLong> loc=txLocations.get(txID);
		if (loc==null) return null;
		return peer.getBlockResult(loc.get(0).longValue()).getResult(loc.get(1).longValue());
	}

	public SignedData<ATransaction> getTransaction(Peer peer, Hash transactionID) {
		AVector<CVMLong> loc=txLocations.get(transactionID);
		if (loc==null) return null;
		return peer.getPeerOrder().getBlock(loc.get(0).longValue()).getValue().getTransactions().get(loc.get(1).longValue());
	}

	public AVector<CVMLong> getTransactionLocation(Hash transactionID) {
		return txLocations.get(transactionID);
	}

	public CVMLong getBlockIndex(Hash blockHash) {
		return blockLocations.get(blockHash);
	}

}
