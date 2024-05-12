package convex.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.util.Utils;
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
	 */
	public CompletableFuture<State> acquireState() throws TimeoutException {
		try {
			Future<Result> sF = requestStatus();
			AVector<ACell> status = sF.get(timeout, TimeUnit.MILLISECONDS).getValue();
			Hash stateHash = RT.ensureHash(status.get(4));

			if (stateHash == null)
				throw new Error("Bad status response from Peer");
			return acquire(stateHash,Stores.current());
		} catch (InterruptedException | ExecutionException e) {
			throw Utils.sneakyThrow(e);
		}
	}
	
	@Override
	public synchronized CompletableFuture<Result> transact(SignedData<ATransaction> signed) throws IOException {
		CompletableFuture<Result> cf;
		long id = -1;
		long wait=1;
		
		// loop until request is queued
		
		while (true) {
			synchronized (awaiting) {
				id = connection.sendTransaction(signed);
				if (id>=0) {
					// Store future for completion by result message
					maybeUpdateSequence(signed);
					cf = awaitResult(id,timeout);
					break;
				} 
			}
			
			try {
				Thread.sleep(wait);
				wait+=1; // linear backoff
			} catch (InterruptedException e) {
				throw Utils.sneakyThrow(e);
			}
		}

		log.trace("Sent transaction with message ID: {} awaiting count = {}", id, awaiting.size());
		return cf;
	}
	


	@Override
	public CompletableFuture<Result> query(ACell query, Address address) throws IOException {
		long wait=1;
		
		// loop until request is queued
		while (true) {
			synchronized (awaiting) {
				long id = connection.sendQuery(query, address);
				if(id>=0) {
					CompletableFuture<Result> cf= awaitResult(id,timeout);
					return cf;
				}
			}
			
			// If we can't send yet, block briefly and try again
			try {
				Thread.sleep(wait);
				wait+=wait; // exponential backoff
			} catch (InterruptedException e) {
				throw new IOException("Transaction sending interrupted",e);
			}
		}
	}
	
	@Override
	public CompletableFuture<Result> requestStatus() {
		try {
			synchronized (awaiting) {
				long id = connection.sendStatusRequest();
				if (id < 0) {
					return CompletableFuture.failedFuture(new IOException("Failed to send status request due to full buffer"));
				}
	
				// TODO: ensure status is fully loaded
				// Store future for completion by result message
				CompletableFuture<Result> cf = awaitResult(id,timeout);
	
				return cf;
			}
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}
	
	@Override
	public CompletableFuture<Result> requestChallenge(SignedData<ACell> data) throws IOException {
		synchronized (awaiting) {
			long id = connection.sendChallenge(data);
			if (id < 0) {
				// TODO: too fragile?
				throw new IOException("Failed to send challenge due to full buffer");
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
