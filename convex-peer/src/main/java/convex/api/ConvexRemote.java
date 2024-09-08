package convex.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.Keywords;
import convex.core.data.SignedData;
import convex.core.exceptions.ResultException;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.net.Connection;
import convex.peer.Server;

public class ConvexRemote extends Convex {
	/**
	 * Current Connection to a Peer, may be null or a closed connection.
	 */
	protected Connection connection;
	
	private static final Logger log = LoggerFactory.getLogger(ConvexRemote.class.getName());
	

	protected InetSocketAddress remoteAddress;
	
	@Override
	public InetSocketAddress getHostAddress() {
		return remoteAddress;
	}

	protected ConvexRemote(Address address, AKeyPair keyPair) {
		super(address, keyPair);
	}
	
	protected void connectToPeer(InetSocketAddress peerAddress, AStore store) throws IOException, TimeoutException {
		remoteAddress=peerAddress;
		setConnection(Connection.connect(peerAddress, messageHandler, store));
	}
	
	public void reconnect() throws IOException, TimeoutException {
		Connection curr=connection;
		AStore store=(curr==null)?Stores.current():curr.getStore();
		close();
		setConnection(Connection.connect(remoteAddress, messageHandler, store));
	}

	/**
	 * Sets the current Connection for this Remote Client
	 *
	 * @param conn Connection value to use
	 */
	protected void setConnection(Connection conn) {
		Connection curr=this.connection;
		if (curr == conn) return;
		if (curr!=null) close();
		this.connection = conn;
	}
	
	/**
	 * Checks if this Convex client instance has an open remote connection.
	 *
	 * @return true if connected, false otherwise
	 */
	public boolean isConnected() {
		Connection c = this.connection;
		return (c != null) && (!c.isClosed());
	}
	
	/**
	 * Close without affecting the underlying connection (will be unlinked but not closed)
	 */
	public void closeButMaintainConnection() {
		this.connection = null;
		close();
	}
	
	/**
	 * Gets the consensus state from the connected Peer. The acquired state will be a snapshot
	 * of the network global state as calculated by the Peer.
	 * 
	 * SECURITY: Be aware that if this client instance is connected to an untrusted Peer, the
	 * Peer may lie about the latest state. If this is a security concern, the client should
	 * validate the consensus state independently.
	 * 
	 * @return Future for consensus state
	 * @throws TimeoutException If initial status request times out
	 * @throws InterruptedException In case of interrupt while acquiring
	 */
	public CompletableFuture<State> acquireState() throws TimeoutException, InterruptedException {
		return requestStatus().thenCompose(status->{
			Hash stateHash = RT.ensureHash(status.get(4));

			if (stateHash == null) {
				return CompletableFuture.failedStage(new ResultException(ErrorCodes.FORMAT,"Bad status response from Peer"));
			}
			return acquire(stateHash,Stores.current());
		});	
	}
	
	@Override
	public synchronized CompletableFuture<Result> transact(SignedData<ATransaction> signed) {
		long id = -1;
		long wait=10;
		
		// loop until request is queued. We need this for backpressure
		while (true) {
			if (connection.isClosed()) return closedResult;
			
			try {
				synchronized (awaiting) {
					id = connection.sendTransaction(signed);
					if (id>=0) {
						// Store future for completion by result message
						maybeUpdateSequence(signed);
						CompletableFuture<Result> cf = awaitResult(id,timeout);
						log.trace("Sent transaction with message ID: {} awaiting count = {}", id, awaiting.size());
						return cf;
					} 
				}
				
				Thread.sleep(wait);
				wait+=1+wait/3; // slow exponential backoff
			} catch (InterruptedException e) {
				// we honour the interruption, but return a failed result
				Result r=Result.fromException(e);
				return CompletableFuture.completedFuture(r);
			} catch (IOException e) {
				Result r=Result.fromException(e).withInfo(Keywords.SOURCE,SourceCodes.COMM);
				return CompletableFuture.completedFuture(r);
			}
		}
	}

	private static CompletableFuture<Result> closedResult=CompletableFuture.completedFuture(Result.error(ErrorCodes.CLOSED, "Transaction interrupted before sending").withSource(SourceCodes.COMM));

	@Override
	public CompletableFuture<Result> query(ACell query, Address address)  {
		long wait=10;
		
		// loop until request is queued. We need this for backpressure
		while (true) {
			if (connection.isClosed()) return closedResult;
			
			// If we can't send yet, block briefly and try again
			try {
				synchronized (awaiting) {
					long id = connection.sendQuery(query, address);
					if(id>=0) {
						CompletableFuture<Result> cf= awaitResult(id,timeout);
						return cf;
					}
				}

				Thread.sleep(wait);
				wait+=1+wait/3; // slow exponential backoff
			} catch (InterruptedException e) {
				// This handles interrupts correctly, returning a failed result
				Result r= Result.fromException(e);
				return CompletableFuture.completedFuture(r);
			} catch (IOException e) {
				Result r=Result.fromException(e).withInfo(Keywords.SOURCE,SourceCodes.COMM);
				return CompletableFuture.completedFuture(r);
			}
		}
	}
	
	@Override
	public CompletableFuture<Result> requestStatus() {
		try {
			synchronized (awaiting) {
				long id = connection.sendStatusRequest();
				if (id < 0) {
					return CompletableFuture.completedFuture(Result.error(ErrorCodes.LOAD, "Full buffer, can't send status request").withSource(SourceCodes.COMM));
				}
	
				CompletableFuture<Result> cf = awaitResult(id,timeout);
				return cf;
			}
		} catch (IOException e) {
			Result r=Result.fromException(e).withInfo(Keywords.SOURCE,SourceCodes.COMM);
			return CompletableFuture.completedFuture(r);
		}
	}
	
	@Override
	public CompletableFuture<Result> requestChallenge(SignedData<ACell> data) {
		synchronized (awaiting) {
			long id;
			try {
				id = connection.sendChallenge(data);
			} catch (IOException e) {
				return CompletableFuture.completedFuture(Result.error(ErrorCodes.IO, "Error requesting challenge"));
			}
			if (id < 0) {
				// TODO: too fragile?
				return CompletableFuture.completedFuture(Result.error(ErrorCodes.IO, "Full buffer while requesting challenge"));
			}

			// Store future for completion by result message
			return awaitResult(id,timeout);
		}
	}
	
	@Override
	public <T extends ACell> CompletableFuture<T> acquire(Hash hash, AStore store) {
		Acquiror acquiror=Acquiror.create(hash, store, this);
		return acquiror.getFuture();

	}
	
	/**
	 * Disconnects the client from the network, closing the underlying connection.
	 */
	public synchronized void close() {
		Connection c = this.connection;
		if (c != null) {
			c.close();
		}
		connection = null;
		awaiting.clear();
	}

	@Override
	public String toString() {
		return "Remote Convex instance at "+getHostAddress();
	}

	@Override
	public Server getLocalServer() {
		return null;
	}
}
