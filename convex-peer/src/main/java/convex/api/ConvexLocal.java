package convex.api;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.exceptions.TODOException;
import convex.core.store.AStore;
import convex.core.transactions.ATransaction;
import convex.peer.Server;

public class ConvexLocal extends Convex {

	private final Server server;

	protected ConvexLocal(Server server, Address address, AKeyPair keyPair) {
		super(address, keyPair);
		this.server=server;
	}

	@Override
	public boolean isConnected() {
		return server.isLive();
	}

	@Override
	public CompletableFuture<Result> transact(SignedData<ATransaction> signed) throws IOException {
		throw new TODOException();
	}

	@Override
	public <T extends ACell> CompletableFuture<T> acquire(Hash hash, AStore store) {
		throw new TODOException();
	}

	@Override
	public CompletableFuture<Result> requestStatus() throws IOException {
		throw new TODOException();
	}

	@Override
	public CompletableFuture<Result> requestChallenge(SignedData<ACell> data) throws IOException {
		throw new TODOException();
	}

	@Override
	public CompletableFuture<Result> query(ACell query, Address address) throws IOException {
		throw new TODOException();
	}

	@Override
	public void close() {
		// Nothing to do
	}

	@Override
	public CompletableFuture<State> acquireState() throws TimeoutException {
		return CompletableFuture.completedFuture(server.getPeer().getConsensusState());
	}

}
