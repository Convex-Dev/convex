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
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.data.SignedData;
import convex.core.lang.Reader;
import convex.core.lang.Symbols;
import convex.core.lang.ops.Lookup;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.util.Utils;
import convex.net.Connection;
import convex.net.Message;
import convex.net.ResultConsumer;

/**
 * Class representing the client API to the Convex network when connected directly using the
 * binary protocol. This can be more efficient than using a REST API.
 * 
 * An Object of the type Convex represents a stateful client connection to the Convex network
 * that can issue transactions both synchronously and asynchronously.
 * 
 * "I'm doing a (free) operating system (just a hobby, won't be big and professional like gnu)"
 * - Linus Torvalds
 */
public class Convex {
	
	// private static final Logger log = Logger.getLogger(Convex.class.getName());

	/**
	 * Key pair for this Client
	 */
	protected final AKeyPair keyPair;
	
	/**
	 * Current Connection to a Peer, may be null or a closed connection.
	 */
	protected Connection connection;
	
	/**
	 * Determines if auto-sequencing should be attempted
	 */
	private boolean autoSequence=true;
	
	/**
	 * Sequence number for this client, or null if not yet known
	 */
	protected Long sequence=null;
	
	private HashMap<Long,CompletableFuture<Result>> awaiting=new HashMap<>();
	
	private Consumer<Message> handler = new ResultConsumer() {
		@Override
		protected synchronized void handleResultMessage(Message m) {
			Result v = m.getPayload();
			long id = m.getID();
			synchronized(awaiting) {
				CompletableFuture<Result> cf=awaiting.get(id);
				if (cf!=null) {
					awaiting.remove(id);
					cf.complete(v);
				} else {
					// TODO: Maybe log that we got a message we weren't expecting?
				}
			}
		}
	};

	private Convex(AKeyPair keyPair) {
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
		Convex convex=new Convex(keyPair);
		convex.connectToPeer(peerAddress);
		return convex;
	}

	/**
	 * Gets the next sequence number for this Client, which should be used for building new signed
	 * transactions
	 * 
	 * @return Sequence number as a Long value greater than zero
	 */
	public long getNextSequence() {
		return getSequence()+1L;
	}
	
	public void setNextSequence(long nextSequence) {
		this.sequence=nextSequence-1L;
	}
	
	/**
	 * Gets the current sequence number for this Client, which is the sequence number of the last 
	 * transaction observed for the current client's Account. 
	 * 
	 * @return Sequence number as a Long value, zero or positive
	 */
	private long getSequence() {
		if (sequence==null) {
			try {
				Future<Result> f=query(Lookup.create(Symbols.STAR_SEQUENCE));
				Result r=f.get();
				if (r.isError()) throw new Error("Error querying *sequence*: "+r.getValue());
				sequence=(Long)(r.getValue());
			} catch (IOException | InterruptedException | ExecutionException e) {
				throw new Error("Error trying to get sequence number",e);
			}
		}
		return sequence;
	}

	private void connectToPeer(InetSocketAddress peerAddress) throws IOException {
		setConnection(Connection.connect(peerAddress, handler, Stores.current()));
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
	 * Gets the Internet address of the currently connected peer
	 * @return
	 */
	public InetSocketAddress getPeerAddress() {
		return connection.getRemoteAddress();
	}
	
	/**
	 * Checks if this Convex client instance has an open connection.
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
	
	/**
	 * Updates the given transaction to have the next sequence number.
	 * @param t Any transaction, for which the correct next sequence number is desired
	 * @return The updated transaction 
	 */
	public ATransaction applyNextSequence(ATransaction t) {
		if (sequence!=null) {
			// if we know the next sequence number to be applied, set it
			return t.withSequence(++sequence);
		} else {
			return t;
		}
	}
	
	/**
	 * Submits a transaction to the Convex network, returning a future once the transaction 
	 * has been successfully queued.
	 * 
	 * @param transaction Transaction to execute
	 * @return A Future for the result of the transaction
	 * @throws IOException If the connection is broken, or the send buffer is full
	 */
	public CompletableFuture<Result> transact(ATransaction transaction) throws IOException {
		if (autoSequence) {
			transaction=applyNextSequence(transaction);
		}
		SignedData<ATransaction> signed=keyPair.signData(transaction);
		return transact(signed);
	}
	
	public CompletableFuture<Result> transact(SignedData<ATransaction> signed) throws IOException {
		CompletableFuture<Result> cf=new CompletableFuture<Result>();
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

	/**
	 * Submits a transaction synchronously to the Convex network, returning a Result
	 * 
	 * @param transaction Transaction to execute
	 * @return The result of the transaction
	 * @throws IOException If the connection is broken
	 * @throws TimeoutException If the attempt to transact with the network is not confirmed within a reasonable time
	 */
	public Result transactSync(SignedData<ATransaction> transaction) throws TimeoutException, IOException {
		return transactSync(transaction,Constants.DEFAULT_CLIENT_TIMEOUT);
	}
	
	/**
	 * Submits a transaction synchronously to the Convex network, returning a Result
	 * 
	 * @param transaction Transaction to execute
	 * @return The result of the transaction
	 * @throws IOException If the connection is broken
	 * @throws TimeoutException If the attempt to transact with the network is not confirmed within a reasonable time
	 */
	public Result transactSync(ATransaction transaction) throws TimeoutException, IOException {
		return transactSync(transaction,Constants.DEFAULT_CLIENT_TIMEOUT);
	}
	
	/**
	 * Submits a signed transaction synchronously to the Convex network, returning a Result
	 * 
	 * @param transaction Transaction to execute
	 * @param timeout Number of milliseconds for timeout
 	 * @return The result of the transaction
	 * @throws IOException If the connection is broken
	 * @throws TimeoutException If the attempt to transact with the network is not confirmed by the specified timeout
	 */
	public Result transactSync(ATransaction transaction, long timeout) throws TimeoutException, IOException {
		// sample time at start of transaction attempt
		long start=Utils.getTimeMillis();
		
		Future<Result> cf=transact(transaction);
		
		// adjust timeout if time elapsed to submit transaction
		long now=Utils.getTimeMillis();
		timeout=Math.max(0L,timeout-(now-start));
		try {
			return cf.get(timeout,TimeUnit.MILLISECONDS);
		} catch (InterruptedException |ExecutionException e) {
			throw new Error("Not possible? Since there is no Thread for the future....",e);
		}
	}
	
	/**
	 * Submits a signed transaction synchronously to the Convex network, returning a Result
	 * 
	 * @param transaction Transaction to execute
	 * @param timeout Number of milliseconds for timeout
 	 * @return The result of the transaction
	 * @throws IOException If the connection is broken
	 * @throws TimeoutException If the attempt to transact with the network is not confirmed by the specified timeout
	 */
	public Result transactSync(SignedData<ATransaction> transaction, long timeout) throws TimeoutException, IOException {
		// sample time at start of transaction attempt
		long start=Utils.getTimeMillis();
		
		Future<Result> cf=transact(transaction);
		
		// adjust timeout if time elapsed to submit transaction
		long now=Utils.getTimeMillis();
		timeout=Math.max(0L,timeout-(now-start));
		try {
			return cf.get(timeout,TimeUnit.MILLISECONDS);
		} catch (InterruptedException |ExecutionException e) {
			throw new Error("Not possible? Since there is no Thread for the future....",e);
		}
	}
	
	/**
	 * Submits a query to the Convex network, returning a Future once the query 
	 * has been successfully queued.
	 * 
	 * @param query Query to execute, as a Form or Op
	 * @return A Future for the result of the query
	 * @throws IOException If the connection is broken, or the send buffer is full
	 */
	public Future<Result> query(Object query) throws IOException {
		return query(query,getAddress());
	}
	
	/**
	 * Submits a query to the Convex network, returning a Future once the query 
	 * has been successfully queued.
	 * 
	 * @param query Query to execute, as a Form or Op
	 * @param address Address to use for the query
	 * @return A Future for the result of the query
	 * @throws IOException If the connection is broken, or the send buffer is full
	 */
	public Future<Result> query(Object query, Address address) throws IOException {
		CompletableFuture<Result> cf=new CompletableFuture<Result>();
		
		long id=connection.sendQuery(query,address);
		if (id<0) {
			throw new IOException("Failed to send query due to full buffer");
		}
		
		// Store future for completion by result message
		synchronized (awaiting) {
			awaiting.put(id,cf);
		}
		
		return cf;
	}

	/**
	 * Executes a query synchronously and waits for the Result
	 * 
	 */
	public Result querySync(Object query) throws TimeoutException, IOException, InterruptedException, ExecutionException {
		return querySync(query,getAddress());
	}
	
	/**
	 * Executes a query synchronously and waits for the Result
	 * 
	 * @param timeoutMillis Timeout to wait for query result. Will throw TimeoutException if not received in this time
	 * @param query Query to execute, as a Form or Op
	 * @return Result of query
	 * @throws TimeoutException 
	 */
	public Result querySync(Object query, long timeoutMillis) throws IOException, InterruptedException, ExecutionException, TimeoutException {
		return querySync(query,getAddress(),timeoutMillis);
	}

	
	/**
	 * Executes a query synchronously and waits for the Result
	 * 
	 * @param address Address to use for the query
	 * @param query Query to execute, as a Form or Op
	 * @return Result of query
	 * @throws TimeoutException 
	 */
	public Result querySync(Object query, Address address) throws IOException, InterruptedException, ExecutionException, TimeoutException {
		return querySync(query,address,Constants.DEFAULT_CLIENT_TIMEOUT);
	}

	/**
	 * Executes a query synchronously and waits for the Result
	 * 
	 * @param timeoutMillis Timeout to wait for query result. Will throw TimeoutException if not received in this time
	 * @param address Address to use for the query
	 * @param query Query to execute, as a Form or Op
	 * @return Result of query
	 */
	public Result querySync(Object query, Address address, long timeoutMillis) throws TimeoutException, IOException, InterruptedException, ExecutionException {
		return query(query,address).get(timeoutMillis,TimeUnit.MILLISECONDS);
	}

	/**
	 * Returns the current Address for the client using the API.
	 * 
	 * @return Address instance
	 */
	private Address getAddress() {
		return keyPair.getAddress();
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

	/**
	 * Determines if this Client is configured to automatically generate sequence numbers
	 * @return
	 */
	protected boolean isAutoSequence() {
		return autoSequence;
	}

	/**
	 * Configures auto-generation of sequence numbers
	 * @param autoSequence true to enable auto-sequencing, false otherwise
	 */
	protected void setAutoSequence(boolean autoSequence) {
		this.autoSequence = autoSequence;
	}

	public Long getBalance(Address address) throws IOException {
		try {
			Future<Result> future= query(Reader.read("(balance 0x"+address.toHexString()+")"));
			Result result=future.get(Constants.DEFAULT_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);
			if (result.isError()) throw new Error(result.toString());
			Long bal= (Long) result.getValue();
			return bal;
		} catch (ExecutionException | InterruptedException | TimeoutException ex) {
			throw new IOException("Unable to query balance",ex);
		}
	}


}
