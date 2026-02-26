package convex.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.exceptions.MissingDataException;
import convex.core.message.LocalConnection;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.util.ThreadUtils;
import convex.peer.Server;

/**
 * Convex Client implementation supporting a connection to a Peer Server in the same JVM.
 *
 * <p>Uses a persistent paired {@link LocalConnection} for bidirectional messaging
 * with the local server. Result correlation and CHALLENGE auto-response are handled
 * by the shared dispatch logic in {@link AConvexConnected}.</p>
 */
public class ConvexLocal extends AConvexConnected {

	private final Server server;

	protected ConvexLocal(Server server, Address address, AKeyPair keyPair) {
		super(address, keyPair);
		this.server=server;
		this.preCompile=true;

		// Create persistent paired connection to server
		Predicate<Message> clientHandler = m -> { returnMessageHandler.accept(m); return true; };
		Predicate<Message> serverHandler = m -> {
			Predicate<Message> retry = server.deliverMessage(m);
			if (retry != null) {
				return retry.test(m);
			}
			return true;
		};
		LocalConnection clientEnd = (keyPair != null)
			? LocalConnection.createPair(clientHandler, serverHandler)       // bidirectional: supports CHALLENGE
			: LocalConnection.createReturnable(clientHandler, serverHandler); // return-only: results only
		setConnection(clientEnd);
	}

	public static ConvexLocal create(Server server) {
		return new ConvexLocal(server, null,null);
	}

	public static ConvexLocal create(Server server, Address address, AKeyPair keyPair) {
		return new ConvexLocal(server, address,keyPair);
	}

	@Override
	public AStore getStore() {
		if (store!=null) return store;
		return server.getStore();
	}

	@Override
	public boolean isConnected() {
		return server.isLive();
	}

	@Override
	public <T extends ACell> CompletableFuture<T> acquire(Hash hash) {
		return acquire(hash, server.getStore());
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> CompletableFuture<T> acquire(Hash hash, AStore store) {
		CompletableFuture<T> f = new CompletableFuture<T>();
		ThreadUtils.runVirtual("local-acquire", ()-> {
			AStore peerStore=server.getStore();
			Ref<ACell> ref=peerStore.refForHash(hash);
			if (ref==null) {
				f.completeExceptionally(new MissingDataException(peerStore,hash));
			} else {
				try {
					ref=store.storeTopRef(ref, Ref.PERSISTED, null);
				} catch (IOException e) {
					f.completeExceptionally(e);
				}
				f.complete((T) ref.getValue());
			}
		});
		return f;
	}

	@Override
	public CompletableFuture<Result> requestStatus() {
		Message m=Message.createStatusRequest(getNextID());
		return message(m);
	}

	@Override
	public CompletableFuture<Result> transact(SignedData<ATransaction> signed) {
		maybeUpdateSequence(signed);
		Message m=Message.createTransaction(getNextID(),signed);
		return message(m);
	}

	@Override
	protected CompletableFuture<Result> sendChallenge(SignedData<ACell> data) {
		Message m=Message.createChallenge(getNextID(), data);
		return message(m);
	}

	@Override
	public CompletableFuture<Result> query(ACell query, Address address) {
		Message m=Message.createQuery(getNextID(),query,address);
		return message(m);
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
	public void close() {
		// Nothing to close for local connection
	}

	@Override
	public CompletableFuture<State> acquireState() {
		return CompletableFuture.completedFuture(getState());
	}

	public State getState() {
		return server.getPeer().getConsensusState();
	}

	@Override
	public Server getLocalServer() {
		return server;
	}

	@Override
	public long getSequence() {
		if (sequence==null) {
			AccountStatus as=getState().getAccount(address);
			if (as==null) return 0;
			sequence=as.getSequence();
		}
		return sequence;
	}

	@Override
	public long getSequence(Address addr) {
		if (Cells.equals(address, addr)) return getSequence();
		return getState().getAccount(addr).getSequence();
	}

	@Override
	public String toString() {
		return "Local Convex instance on "+server.getHostAddress();
	}

	@Override
	public InetSocketAddress getHostAddress() {
		return server.getHostAddress();
	}

	@Override
	public Long getBalance() {
		return server.getPeer().getConsensusState().getBalance(address);
	}

	@Override
	public void reconnect()  {
		// Always connected
	}
}
