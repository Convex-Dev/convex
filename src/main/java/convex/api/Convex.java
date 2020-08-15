package convex.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import convex.core.Constants;
import convex.core.crypto.AKeyPair;
import convex.core.data.AVector;
import convex.core.data.SignedData;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.net.Connection;
import convex.net.Message;
import convex.net.ResultConsumer;

/**
 * Class representing the client API to the Convex network
 * 
 * "I'm doing a (free) operating system (just a hobby, won't be big and professional like gnu)"
 * - Linus Torvalds
 */
public class Convex {
	
	// private static final Logger log = Logger.getLogger(Convex.class.getName());

	private final AKeyPair keyPair;
	private Connection connection;
	
	private HashMap<Long,CompletableFuture<AVector<Object>>> awaiting=new HashMap<>();
	
	private Consumer<Message> handler = new ResultConsumer() {
		@Override
		protected synchronized void handleResultMessage(Message m) {
			AVector<Object> v = m.getPayload();
			long id = m.getID();
			synchronized(awaiting) {
				CompletableFuture<AVector<Object>> cf=awaiting.get(id);
				if (cf!=null) {
					awaiting.remove(id);
					cf.complete(v);
				}
			}
		}
	};

	private Convex(InetSocketAddress peerAddress, AKeyPair keyPair) {
		this.keyPair=keyPair;
	}

	/**
	 * Create a Convex client by connecting to the specified Peer using the given key pair
	 *
	 * @param peerAddress Address of Peer
	 * @param keyPair Key pair to use for client transactions
	 * @return New Convex client instance
	 * @throws IOException If connection fails
	 */
	public static Convex connect(InetSocketAddress peerAddress, AKeyPair keyPair) throws IOException {
		Convex convex=new Convex(peerAddress,keyPair);
		convex.connectToPeer(peerAddress);
		return convex;
	}

	private void connectToPeer(InetSocketAddress peerAddress) throws IOException {
		setConnection(Connection.connect(peerAddress, handler, Stores.CLIENT_STORE));
	}
	
	/**
	 * Signs a value on behalf of this client.
	 * 
	 * @param <T> Type of value to sign
	 * @param value Value to sign
	 * @return
	 */
	public <T> SignedData<T> signData(T value) {
		return keyPair.signData(value);
	}

	/**
	 * Gets the address for the currently connected peer
	 * @return
	 */
	public InetSocketAddress getPeerAddress() {
		return connection.getRemoteAddress();
	}
	
	/**
	 * Returns true if this Convex client instance has a non-null connection that is open,
	 * false otherwise.
	 * 
	 * @return true if connected, false otherwise
	 */
	public boolean isConnected() {
		Connection c=this.connection;
		return (c!=null)&&(!c.isClosed());
	}

	/**
	 * Gets the underlying Connection instance for this Client. May be null if not connected.
	 * @return Connection instance or null
	 */
	public Connection getConnection() {
		return connection;
	}
	
	public Future<AVector<Object>> transact(ATransaction transaction) throws IOException {
		CompletableFuture<AVector<Object>> cf=new CompletableFuture<AVector<Object>>();
		
		SignedData<ATransaction> signed=keyPair.signData(transaction);
		
		long id=connection.sendTransaction(signed);
		if (id<0) {
			throw new IOException("Failed to send transaction due to full buffer");
		}
		
		// Store future for completion by result message
		synchronized (awaiting) {
			awaiting.put(id,cf);
		}
		
		return cf;
	}
	
	public AVector<Object> transactSync(ATransaction transaction) throws TimeoutException, IOException {
		return transactSync(transaction,Constants.DEFAULT_CLIENT_TIMEOUT);
	}
	
	public AVector<Object> transactSync(ATransaction transaction, long timeout) throws TimeoutException, IOException {
		Future<AVector<Object>> cf=transact(transaction);
		try {
			return cf.get(timeout,TimeUnit.MILLISECONDS);
		} catch (InterruptedException |ExecutionException e) {
			throw new Error("Not possible? Since there is no Thread for the future....");
		}
	}


	/**
	 * Sets the current Connection for this Client
	 * @param conn Connection value to use
	 */
	private void setConnection(Connection conn) {
		if (this.connection==conn) return;
		disconnect();
		this.connection = conn;
	}

	/**
	 * Disconnects the client from the network.
	 */
	public void disconnect() {
		Connection c=this.connection;
		if (c!=null) {
			c.close();
		}
		connection=null;
	}
}
