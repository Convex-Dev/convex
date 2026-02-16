package convex.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.data.SignedData;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.TODOException;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.net.AConnection;
import convex.net.impl.netty.NettyConnection;
import convex.net.impl.nio.Connection;
import convex.peer.Config;
import convex.peer.Server;

/**
 * Convex client API implementation for peers accessed over a network connection using the Convex binary peer protocol
 * 
 */
public class ConvexRemote extends Convex {
	/**
	 * Current Connection to a Peer, may be null or a closed connection.
	 */
	protected AConnection connection;
	
	protected static final Logger log = LoggerFactory.getLogger(ConvexRemote.class.getName());
	

	protected InetSocketAddress remoteAddress;
	
	@Override
	public InetSocketAddress getHostAddress() {
		return remoteAddress;
	}

	protected ConvexRemote(Address address, AKeyPair keyPair) {
		super(address, keyPair);
	}
	
	protected void connectToPeer(InetSocketAddress peerAddress) throws IOException, TimeoutException, InterruptedException {
		remoteAddress=peerAddress;
		if (Config.USE_NETTY_CLIENT) {
			setConnection(NettyConnection.connect(peerAddress, returnMessageHandler));
		} else {
			setConnection(Connection.connect(peerAddress, returnMessageHandler));
		}
		// setConnection(NettyConnection.connect(peerAddress, returnMessageHandler));
	}
	
	public static ConvexRemote connect(InetSocketAddress peerAddress) throws IOException, TimeoutException, InterruptedException {
		ConvexRemote convex=new ConvexRemote(null,null);
		convex.connectToPeer(peerAddress);
		return convex;
	}
	
	public static ConvexRemote connectNetty(InetSocketAddress sa) throws InterruptedException, IOException {
		ConvexRemote convex=new ConvexRemote(null,null);
		convex.remoteAddress=sa;
		convex.setConnection(NettyConnection.connect(sa, convex.returnMessageHandler));
		return convex;
	}
	
	public static ConvexRemote connectNIO(InetSocketAddress sa) throws InterruptedException, IOException, TimeoutException {
		ConvexRemote convex=new ConvexRemote(null,null);
		convex.remoteAddress=sa;
		convex.setConnection(Connection.connect(sa, convex.returnMessageHandler));
		return convex;
	}
	
	/**
	 * Map of results awaiting completion.
	 */
	private HashMap<ACell, CompletableFuture<Message>> awaiting = new HashMap<>();

	/**
	 * Method to start waiting for a return Message. 
	 * 
	 * Must be called with lock on
	 * `awaiting` map to prevent risk of missing results before it is called.
	 * 
	 * @param resultID ID of result message to await
	 * @return
	 */
	private CompletableFuture<Result> awaitResult(ACell resultID, long timeout) {
		if (resultID==null) throw new IllegalArgumentException("Non-null return ID required");

		CompletableFuture<Message> cf = new CompletableFuture<Message>();
		awaiting.put(resultID, cf);

		if (timeout>0) {
			cf=cf.orTimeout(timeout, TimeUnit.MILLISECONDS);
		}
		CompletableFuture<Result> cr=cf.handle((m,e)->{
			synchronized(awaiting) {
				awaiting.remove(resultID);
			}

			if (e!=null) {
				sequence=null;
				return Result.fromException(e);
			}

			try {
				m.getPayload(getStore()); // null → storeless, non-null → store-based for partial messages
			} catch (BadFormatException e1) {
				log.warn("Bad message format in result: {}",e1.getMessage());
				sequence=null;
				return Result.error(ErrorCodes.FORMAT, Strings.create("Bad message format: "+e1.getMessage()));
			}
			Result r=m.toResult();
			if (r.getErrorCode()!=null) {
				sequence=null;
			}
			return r;
		});
		return cr;
	}

	/**
	 * Result handler for Messages received back from a remote connection.
	 * Completes the awaiting future — no store context needed.
	 */
	protected final Consumer<Message> returnMessageHandler = m-> {
		try {
			ACell id=m.getResultID();

			if (id!=null) {
				synchronized (awaiting) {
					CompletableFuture<Message> cf = awaiting.get(id);
					if (cf != null) {
						boolean didComplete = cf.complete(m);
						if (!didComplete) {
							log.warn("Message return future already completed with value: "+cf.join());
						}
						awaiting.remove(id);
					}
				}
			}
		} catch (Exception e) {
			log.warn("Error in return message handler: {}",e.getMessage());
		}
	};
	

	
	public synchronized void reconnect() throws IOException, TimeoutException, InterruptedException {
		close();
		connectToPeer(remoteAddress);
	}

	/**
	 * Sets the current Connection for this Remote Client
	 *
	 * @param conn Connection value to use
	 */
	protected void setConnection(AConnection conn) {
		AConnection curr=this.connection;
		if (curr == conn) return; // no change
		if (curr!=null) close();
		this.connection = conn;
	}
	
	/**
	 * Checks if this Convex client instance has an open remote connection.
	 *
	 * @return true if connected, false otherwise
	 */
	public boolean isConnected() {
		AConnection c = this.connection;
		return (c != null) && (!c.isClosed());
	}
	
	@Override
	public CompletableFuture<Result> transact(SignedData<ATransaction> signed) {
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
		AConnection conn=connection;
		if (conn==null) {
			return CompletableFuture.completedFuture(Result.CLOSED_CONNECTION);
		}
		
		ACell id=m.getRequestID();
		try {
			if (id==null) {
				// Not expecting any return message, so just report sending
				boolean sent = conn.sendMessage(m);
				if (sent) {
					// log.info("Sent message: "+m);
				} else {
					return CompletableFuture.completedFuture(Result.FULL_CLIENT_BUFFER);
				}
				return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
			}
			
			synchronized (awaiting) {
				boolean sent = conn.sendMessage(m);				
				if (sent) {
					// All OK
					// log.info("Sent message: "+m);
				} else {
					return CompletableFuture.completedFuture(Result.FULL_CLIENT_BUFFER);
				}
	
				// Make sure we call this while synchronised on awaiting map
				CompletableFuture<Result> cf = awaitResult(id,timeout);
				return cf;
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
		Message m=Message.createChallenge(data);
		return message(m);
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
		AConnection c = this.connection;
		if (c != null) {
			// log.info("Connection closed",new Exception());
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
