package convex.java;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.store.AStore;
import convex.core.transactions.ATransaction;

public class ConvexHTTP extends convex.api.Convex {
	
	private final URI uri;

	protected ConvexHTTP(Address address, AKeyPair keyPair, URI uri) {
		super(address, keyPair);
		this.uri=uri;
	}

	@Override
	public boolean isConnected() {
		return true;
	}

	@Override
	public CompletableFuture<Result> transact(SignedData<ATransaction> signedTransaction) {
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

	@Override
	public CompletableFuture<Result> requestChallenge(SignedData<ACell> data) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CompletableFuture<Result> query(ACell query, Address address) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void close() {
		// nothing to do?
	}

	@Override
	public String toString() {
		return "Convex HTTP connection to peer "+uri;
	}

	@Override
	public InetSocketAddress getHostAddress() {
		Integer port=uri.getPort();
		if (port==-1) {
			if ("https".equals(uri.getScheme())) {
				port =443;
			} else {
				port =8080;
			}
		}
		return new InetSocketAddress(uri.getHost(),port);
	}

}
