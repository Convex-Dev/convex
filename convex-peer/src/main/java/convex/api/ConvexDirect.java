package convex.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.cpos.Order;
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
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.Utils;
import convex.peer.Server;

/**
 * Convex API instance that directly interacts with an in-memory Peer instance.
 *
 * <p>Avoids the network and message queues: every request is handled synchronously
 * on the caller's thread against the Peer, and every future returned is already
 * complete. Mainly useful for testing and forked simulations.</p>
 *
 * <p>Every protocol message type is accepted through {@link #message(Message)} and
 * answered as a Peer server would answer it, so code written against the message
 * API behaves the same way here: a BELIEF is merged into the Peer, a DATA_REQUEST is
 * served from {@link #getStore()}, STATUS reports the Peer's status vector. The Peer
 * itself persists nothing, so data is only acquirable by hash once something has
 * stored it in that store, which is the configured store or the global store.</p>
 */
public class ConvexDirect extends Convex {

	protected volatile Peer peer;
	private volatile boolean isConnected=true;

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

	/**
	 * Gets the current Peer this direct client operates on. The Peer is immutable;
	 * transactions and Belief merges replace it.
	 * @return Current Peer
	 */
	public Peer getPeer() {
		return peer;
	}

	@Override
	public boolean isConnected() {
		return isConnected;
	}

	/**
	 * Gets the store this client serves data from: the configured store, or the
	 * global store if none is configured.
	 */
	@Override
	public AStore getStore() {
		AStore s=store;
		if (s!=null) return s;
		return Stores.getGlobalStore();
	}

	@Override
	public CompletableFuture<Result> transact(SignedData<ATransaction> signedTransaction) {
		CVMLong id=CVMLong.create(getNextID());
		return CompletableFuture.completedFuture(executeTransaction(signedTransaction).withID(id));
	}

	/**
	 * Executes a transaction against the Peer: proposes it in its own Block and
	 * brings this single-peer view to consensus on it.
	 *
	 * @param signedTransaction Signed transaction to execute
	 * @return Result of the transaction, without an ID
	 */
	protected synchronized Result executeTransaction(SignedData<ATransaction> signedTransaction) {
		try {
			Peer p=peer;
			Result failure=p.checkTransaction(signedTransaction);
			if (failure!=null) return failure;

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
			return result;
		} catch (Exception e) {
			return Result.fromException(e);
		}
	}

	/**
	 * Merges a received Belief, or a single signed Order, into the Peer, as a Peer
	 * server does with a BELIEF message. Orders with bad signatures are refused.
	 *
	 * @param payload Belief or signed Order
	 * @return {@link Result#SENT_MESSAGE} if merged, otherwise an error Result
	 */
	protected synchronized Result mergeBelief(ACell payload) {
		try {
			Collection<SignedData<Order>> orders=Belief.extractOrders(payload);
			if (orders.isEmpty()) return Result.error(ErrorCodes.FORMAT, "Not a Belief or signed Order");
			HashMap<AccountKey,SignedData<Order>> orderMap=new HashMap<>();
			for (SignedData<Order> so: orders) {
				if (!so.checkSignature()) return Result.error(ErrorCodes.SIGNATURE, "Bad Order signature");
				orderMap.put(so.getAccountKey(), so);
			}
			Peer p=peer.updateTimestamp(Utils.getCurrentTimestamp());
			p=p.mergeBeliefs(Belief.create(orderMap));
			p=p.updateState();
			peer=p;
			return Result.SENT_MESSAGE;
		} catch (Exception e) {
			return Result.fromException(e);
		}
	}

	@Override
	public CompletableFuture<Result> messageRaw(Blob rawData) {
		try {
			Message m=Message.create(rawData);
			m.getPayload(getStore());
			return message(m);
		} catch (Exception e) {
			return CompletableFuture.completedFuture(Result.fromException(e).withSource(SourceCodes.CLIENT));
		}
	}

	@Override
	public CompletableFuture<Result> message(Message message) {
		return CompletableFuture.completedFuture(handle(message));
	}

	/**
	 * Handles one message synchronously as a Peer server would and returns the Result
	 * the server would return. Messages that expect no response yield
	 * {@link Result#SENT_MESSAGE}. Never returns null.
	 *
	 * @param m Message to handle
	 * @return Result of handling the message
	 */
	@SuppressWarnings("unchecked")
	protected Result handle(Message m) {
		if (m==null) return Result.error(ErrorCodes.FORMAT, "Null message");
		if (!isConnected) return Result.error(ErrorCodes.CLOSED, "Direct connection is closed");
		ACell id=null;
		try {
			id=m.getID();
			MessageType type=m.getType();
			switch (type) {
			case QUERY: {
				AVector<ACell> v=RT.ensureVector(m.getPayload());
				if (v==null || v.count()<3) return Result.error(ErrorCodes.FORMAT, "Invalid query message").withID(id);
				Address address=(v.count()>3)?RT.ensureAddress(v.get(3)):null;
				return Result.fromContext(id, peer.executeQuery(v.get(2), address));
			}
			case TRANSACT: {
				AVector<ACell> v=RT.ensureVector(m.getPayload());
				if (v==null || v.count()<3 || !(v.get(2) instanceof SignedData<?> sd)) {
					return Result.error(ErrorCodes.FORMAT, "Invalid transaction message").withID(id);
				}
				return executeTransaction((SignedData<ATransaction>) sd).withID(id);
			}
			case STATUS:
				return Result.create(id, Server.getStatusData(peer));
			case PING:
				return Result.create(id, CVMLong.create(Utils.getCurrentTimestamp()));
			case CHALLENGE: {
				AVector<ACell> v=RT.ensureVector(m.getPayload());
				if (v==null || v.count()!=3 || !(v.get(2) instanceof SignedData<?> sd)) {
					return Result.error(ErrorCodes.FORMAT, "Invalid challenge message").withID(id);
				}
				return Message.answerChallenge(peer.getKeyPair(), (SignedData<ACell>) sd, null).withID(id);
			}
			case DATA_REQUEST:
				return m.makeDataResponse(getStore()).toResult();
			case BELIEF:
				return mergeBelief(m.getPayload());
			case GOODBYE:
				close();
				return Result.SENT_MESSAGE;
			case DATA: case RESULT: case COMMAND:
				// Nothing for a direct client to do with these
				return Result.SENT_MESSAGE;
			default:
				return Result.error(ErrorCodes.FORMAT, "Unrecognised message type: "+type).withID(id);
			}
		} catch (Exception e) {
			return Result.fromException(e).withID(id);
		}
	}

	/**
	 * Acquires a value by hash from this client's store into the given store.
	 * Completes exceptionally with {@link MissingDataException} if this client does
	 * not hold the value.
	 */
	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> CompletableFuture<T> acquire(Hash hash, AStore store) {
		CompletableFuture<T> f=new CompletableFuture<>();
		try {
			AStore source=getStore();
			Ref<ACell> ref=(source==null)?null:source.refForHash(hash);
			if (ref==null) {
				f.completeExceptionally(new MissingDataException(source,hash));
			} else {
				if (store!=null && store!=source) ref=store.storeTopRef(ref, Ref.PERSISTED, null);
				f.complete((T) ref.getValue());
			}
		} catch (IOException e) {
			f.completeExceptionally(e);
		}
		return f;
	}

	/**
	 * Returns the Peer's consensus State directly. No store or status round trip is
	 * needed for a direct client.
	 */
	@Override
	public CompletableFuture<State> acquireState() {
		return CompletableFuture.completedFuture(peer.getConsensusState());
	}

	@Override
	public CompletableFuture<Result> requestStatus() {
		return request(Message.createStatusRequest((CVMLong)null));
	}

	@Override
	protected CompletableFuture<Result> sendChallenge(SignedData<ACell> data) {
		// Direct transport uses the same signature, nonce and audience checks as TCP.
		return CompletableFuture.completedFuture(
			Message.answerChallenge(peer.getKeyPair(),data,null));
	}

	@Override
	public CompletableFuture<Result> query(ACell query, Address address) {
		CVMLong id=CVMLong.create(getNextID());
		return CompletableFuture.completedFuture(Result.fromContext(id, peer.executeQuery(query, address)));
	}

	@Override
	public void close() {
		isConnected=false;
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
