package convex.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.MissingDataException;
import convex.core.store.AStore;
import convex.core.transactions.ATransaction;
import convex.core.util.Utils;
import convex.net.MessageType;
import convex.net.message.MessageLocal;
import convex.peer.Server;

/**
 * Convex Client implementation supporting a direct connection to a Peer Server in the same JVM.
 */
public class ConvexLocal extends Convex {

	private final Server server;

	protected ConvexLocal(Server server, Address address, AKeyPair keyPair) {
		super(address, keyPair);
		this.server=server;
	}
	
	public static ConvexLocal create(Server server, Address address, AKeyPair keyPair) {
		return new ConvexLocal(server, address,keyPair);
	}

	@Override
	public boolean isConnected() {
		return server.isLive();
	}


	@Override
	public <T extends ACell> CompletableFuture<T> acquire(Hash hash, AStore store) {
		CompletableFuture<T> f = new CompletableFuture<T>();
		new Thread(new Runnable() {
			@SuppressWarnings("unchecked")
			@Override
			public void run() {
				AStore remoteStore=server.getStore();
				Ref<ACell> ref=remoteStore.refForHash(hash);
				if (ref==null) {
					f.completeExceptionally(new MissingDataException(remoteStore,hash));
				} else {
					ref=store.storeTopRef(ref, Ref.PERSISTED, null);
					f.complete((T) ref.getValue());
				}
			}
		}).run();
		return f;
	}

	@Override
	public CompletableFuture<Result> requestStatus() {
		return makeMessageFuture(MessageType.STATUS,CVMLong.create(makeID()));
	}
	
	@Override
	public CompletableFuture<Result> transact(SignedData<ATransaction> signed) {
		CompletableFuture<Result> r= makeMessageFuture(MessageType.TRANSACT,Vectors.of(makeID(),signed));
		maybeUpdateSequence(signed);
		return r;
	}


	@Override
	public CompletableFuture<Result> requestChallenge(SignedData<ACell> data) {
		return makeMessageFuture(MessageType.CHALLENGE,data);
	}

	@Override
	public CompletableFuture<Result> query(ACell query, Address address) {
		return makeMessageFuture(MessageType.QUERY,Vectors.of(makeID(),query,address));
	}
	
	private long idCounter=0;
	
	private long makeID() {
		return idCounter++;
	}

	private CompletableFuture<Result> makeMessageFuture(MessageType type, ACell payload) {
		CompletableFuture<Result> cf=new CompletableFuture<>();
		Consumer<Result> resultHandler=makeResultHandler(cf);
		MessageLocal ml=MessageLocal.create(type,payload, server, resultHandler);
		try {
			server.queueMessage(ml);
		} catch (InterruptedException e) {
			cf.completeExceptionally(e);
		}
		return cf;
		
	}

	private Consumer<Result> makeResultHandler(CompletableFuture<Result> cf) {
		return r->{
			cf.complete(r);
		};
	}

	@Override
	public void close() {
		// Nothing to do
	}

	@Override
	public CompletableFuture<State> acquireState() throws TimeoutException {
		return CompletableFuture.completedFuture(getState());
	}
	
	private State getState() {
		return server.getPeer().getConsensusState();
	}
	
	@Override
	public long getSequence() {
		if (sequence==null) {
			sequence=getState().getAccount(address).getSequence();
		}
		return sequence;
	}
	
	@Override
	public long getSequence(Address addr) {
		if (Utils.equals(address, addr)) return getSequence();
		return getState().getAccount(addr).getSequence();
	}

	@Override
	public String toString() {
		return "Local Convex instance on "+server.getHostAddress();
	}

}
