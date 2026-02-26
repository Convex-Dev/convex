package convex.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.ResultContext;
import convex.core.cpos.Block;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Peer;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Vectors;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.util.Utils;

/**
 * Convex API instance that directly interacts with a Peer instance
 */
public class ConvexDirect extends Convex {

	protected Peer peer;
	private boolean isConnected=true;

	protected ConvexDirect(Address address, AKeyPair keyPair, Peer initial) {
		super(address, keyPair);
		this.peer=initial;
	}
	
	public static ConvexDirect create(AKeyPair peerKey,State state) {
		AccountKey key=peerKey.getAccountKey();
		PeerStatus ps= state.getPeer(key);
		if (ps==null) throw new IllegalStateException("Peer does not exist in desired state");
		Address cont=ps.getController();
		return new ConvexDirect(cont,peerKey,Peer.create(peerKey, state));
	}

	@Override
	public boolean isConnected() {
		return isConnected;
	}

	@Override
	public synchronized CompletableFuture<Result> transact(SignedData<ATransaction> signedTransaction) {
		try {
			CVMLong id=CVMLong.create(getNextID());
			Peer p=peer;
			Result failure=p.checkTransaction(signedTransaction);
			if (failure!=null) {
				return CompletableFuture.completedFuture(failure.withID(id));
			}
			
			long ts=Utils.getCurrentTimestamp();
			Block block=Block.of(ts, signedTransaction);
		
			// Peer updates
			p=p.updateTimestamp(ts);
			p=p.proposeBlock(block);
			p=p.mergeBeliefs();
			p=p.mergeBeliefs();
			p=p.mergeBeliefs();
			p=p.updateState();
			long blockNum=p.getPeerOrder().getBlockCount()-1;
			peer=p;
			
			Result result= p.getResult(blockNum, 0);
			if (result==null) {
				result=Result.error(ErrorCodes.UNEXPECTED, "No result available?");
			}
			return CompletableFuture.completedFuture(result.withID(id));
		} catch (Exception e) {
			return CompletableFuture.completedFuture(Result.fromException(e));
		}
	}

	@Override
	public CompletableFuture<Result> messageRaw(Blob message) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CompletableFuture<Result> message(Message message) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <T extends ACell> CompletableFuture<T> acquire(Hash hash, AStore store) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CompletableFuture<Result> requestStatus() {
		// TODO Auto-generated method stub
		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected CompletableFuture<Result> sendChallenge(SignedData<ACell> data) {
		// Optimised direct path: respond using the local peer's key pair
		// without going through Message return routing
		try {
			AKeyPair peerKP = peer.getKeyPair();
			if (peerKP == null) return CompletableFuture.completedFuture(Result.error(ErrorCodes.TRUST, "No peer key"));
			AVector<ACell> challengeValues = (AVector<ACell>) data.getValue();
			long n = challengeValues.count();
			ACell token = challengeValues.get(0);
			AccountKey challengerKey = data.getAccountKey();
			ACell contextID = (n == 3) ? challengeValues.get(2) : null;

			AVector<ACell> responseValues = (contextID != null)
				? Vectors.of(token, challengerKey, contextID)
				: Vectors.of(token, challengerKey);
			SignedData<ACell> response = peerKP.signData(responseValues);
			return CompletableFuture.completedFuture(Result.value(response));
		} catch (Exception e) {
			return CompletableFuture.completedFuture(Result.fromException(e));
		}
	}

	@Override
	public CompletableFuture<Result> query(ACell query, Address address) {
		CVMLong id=CVMLong.create(getNextID());
		ResultContext rc= peer.executeQuery(query, address);
		return CompletableFuture.completedFuture(Result.fromContext(id,rc));
	}

	@Override
	public void close() {
		peer=null;
	}

	@Override
	public String toString() {
		return "Direct client with peer state: "+peer.getConsensusState().getHash();
	}

	@Override
	public InetSocketAddress getHostAddress() {
		return null;
	}

	@Override
	public void reconnect() throws IOException, TimeoutException, InterruptedException {
		isConnected=true;
	}



}
