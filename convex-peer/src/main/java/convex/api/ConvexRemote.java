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
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ResultException;
import convex.core.exceptions.TODOException;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.net.Connection;
import convex.net.Message;
import convex.peer.Server;

public class ConvexRemote extends Convex {
	/**
	 * Current Connection to a Peer, may be null or a closed connection.
	 */
	protected Connection connection;
	
	protected static final Logger log = LoggerFactory.getLogger(ConvexRemote.class.getName());
	

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
	
	@Override
	public CompletableFuture<State> acquireState() {
		AStore store=Stores.current();
		return requestStatus().thenCompose(status->{
			Hash stateHash = RT.ensureHash(status.get(4));

			if (stateHash == null) {
				return CompletableFuture.failedStage(new ResultException(ErrorCodes.FORMAT,"Bad status response from Peer"));
			}
			return acquire(stateHash,store);
		});	
	}
	
	@Override
	public synchronized CompletableFuture<Result> transact(SignedData<ATransaction> signed) {
		Message m=Message.createTransaction(getNextID(), signed);
		return message(m);
	}

	@Override
	public CompletableFuture<Result> query(ACell query, Address address)  {
		Message m=Message.createQuery(getNextID(), query,address);
		return message(m);
	}
	
	@Override
	public CompletableFuture<Result> messageRaw(Blob message) {
		throw new TODOException();
	}
	
	@Override
	public CompletableFuture<Result> message(Message m) {
		ACell id=m.getID();
		try {
			synchronized (awaiting) {
				if (connection==null) return CompletableFuture.completedFuture(Result.CLOSED_CONNECTION);
				boolean sent = connection.sendMessage(m);
				if (!sent) {
					return CompletableFuture.completedFuture(Result.error(ErrorCodes.LOAD, Strings.FULL_BUFFER).withSource(SourceCodes.COMM));
				}
	
				if (id!=null) {
					CompletableFuture<Result> cf = awaitResult(id,timeout).thenApply(msg->msg.toResult());
					return cf;
				} else {
					Result r=Result.create(id, Strings.SENT);
					return CompletableFuture.completedFuture(r);
				}
			}
		} catch (Exception e) {
			Result r=Result.fromException(e).withInfo(Keywords.SOURCE,SourceCodes.COMM);
			return CompletableFuture.completedFuture(r);
		}
	}
	
	@Override
	public CompletableFuture<Result> requestStatus() {
		Message m=Message.createStatusRequest(getNextID());
		return message(m);
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
			return awaitResult(CVMLong.create(id),timeout).thenApply(msg->msg.toResult());
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
