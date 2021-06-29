package convex.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import convex.core.Constants;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.Keywords;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.lang.Symbols;
import convex.core.lang.ops.Lookup;
import convex.core.lang.ops.Special;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.transactions.Transfer;
import convex.core.util.Utils;
import convex.net.Connection;
import convex.net.Message;
import convex.net.ResultConsumer;
import convex.peer.Server;

/**
 * Class representing the client API to the Convex network when connected
 * directly using the binary protocol. This can be more efficient than using a
 * REST API.
 *
 * An Object of the type Convex represents a stateful client connection to the
 * Convex network that can issue transactions both synchronously and
 * asynchronously. This can be used by both peers and JVM-based clients.
 *
 * "I'm doing a (free) operating system (just a hobby, won't be big and
 * professional like gnu)" - Linus Torvalds
 */
@SuppressWarnings("unused")
public class Convex {

	private static final Logger log = Logger.getLogger(Convex.class.getName());

	private static final Level LEVEL_AQUIRE = Level.FINER;

	/**
	 * Key pair for this Client
	 */
	protected AKeyPair keyPair;

	/**
	 * Current address for this Client
	 */
	protected Address address;

	/**
	 * Current Connection to a Peer, may be null or a closed connection.
	 */
	protected Connection connection;

	/**
	 * Determines if auto-sequencing should be attempted
	 */
	private boolean autoSequence = true;

	/**
	 * Sequence number for this client, or null if not yet known
	 */
	protected Long sequence = null;

	private HashMap<Long, CompletableFuture<Result>> awaiting = new HashMap<>();

	private final Consumer<Message> internalHandler = new ResultConsumer() {
		@Override
		protected synchronized void handleResultMessage(Message m) {
			Result v = m.getPayload();

			if ((v!=null)&&(Keywords.SEQUENCE.equals(v.getErrorCode()))) {
				// We probably got a wrong sequence number. Kill the stored value.
				sequence=null;
			}
			long id = m.getID().longValue();
			synchronized (awaiting) {
				CompletableFuture<Result> cf = awaiting.get(id);
				if (cf != null) {
					awaiting.remove(id);
					cf.complete(v);
				} else {
					log.warning(
							"Unexpected result received for message ID: " + id + " - was not expecting this message");
				}
			}

		}

		@Override
		public void accept(Message m) {
			super.accept(m);

			if (delegatedHandler != null) {
				try {
					delegatedHandler.accept(m);
				} catch (Throwable t) {
					log.warning("Exception thrown in user-supplied handler function:" + t);
				}
			}
		}
	};

	private Consumer<Message> delegatedHandler = null;

	private Convex(Address address, AKeyPair keyPair) {
		this.keyPair = keyPair;

		// TODO: numeric address
		this.address = address;
	}

	/**
	 * Create a Convex client by connecting to the specified Peer using the given
	 * key pair
	 *
	 * @param peerAddress Address of Peer
	 * @param address Address of Account to use for Client
	 * @param keyPair     Key pair to use for client transactions
	 * @return New Convex client instance
	 * @throws IOException If connection fails
	 */
	public static Convex connect(InetSocketAddress peerAddress, Address address, AKeyPair keyPair) throws IOException {
		return Convex.connect(peerAddress, address, keyPair, Stores.current());
	}

	/**
	 * Create a Convex client by connecting to the specified Peer using the given
	 * key pair and using a given store
	 *
	 * @param peerAddress Address of Peer
	 * @param address Address of Account to use for Client
	 * @param keyPair     Key pair to use for client transactions
	 * @param store   Store to use for this connection
	 * @return New Convex client instance
	 * @throws IOException If connection fails
	 */
	public static Convex connect(InetSocketAddress peerAddress, Address address, AKeyPair keyPair, AStore store) throws IOException {
		Convex convex = new Convex(address, keyPair);
		convex.connectToPeer(peerAddress, store);
		return convex;
	}

	/**
	 * Sets the Address for this connection. This will be used by default for
	 * subsequent transactions and queries
	 *
	 * @param address Address to use
	 */
	public synchronized void setAddress(Address address) {
		if (this.address == address) return;
		this.address = address;
		// clear sequence, since we don't know the new account sequence number yet
		sequence = null;
	}

	public synchronized void setAddress(Address addr, AKeyPair kp) {
		setAddress(addr);
		setKeyPair(kp);
	}

	public synchronized void setKeyPair(AKeyPair kp) {
		this.keyPair = kp;
	}

	/**
	 * Gets the next sequence number for this Client, which should be used for
	 * building new signed transactions
	 *
	 * @return Sequence number as a Long value greater than zero
	 */
	private long getIncrementedSequence() {
		long next = getSequence() + 1L;
		sequence = next;
		return next;
	}

	public void setNextSequence(long nextSequence) {
		this.sequence = nextSequence - 1L;
	}

	public void setHandler(Consumer<Message> handler) {
		this.delegatedHandler = handler;
	}

	/**
	 * Gets the current sequence number for this Client, which is the sequence
	 * number of the last transaction observed for the current client's Account.
	 *
	 * @return Sequence number as a Long value, zero or positive
	 */
	public long getSequence() {
		if (sequence == null) {
			try {
				Future<Result> f = query(Special.forSymbol(Symbols.STAR_SEQUENCE));
				Result r = f.get();
				if (r.isError()) throw new Error("Error querying *sequence*: " + r.getErrorCode() + " " + r.getValue());
				ACell result=r.getValue();
				if (!(result instanceof CVMLong)) throw new Error("*sequence* query did not return Long, got: "+result);
				sequence = RT.jvm(result);
			} catch (IOException | InterruptedException | ExecutionException e) {
				throw new Error("Error trying to get sequence number", e);
			}
		}
		return sequence;
	}

	private void connectToPeer(InetSocketAddress peerAddress, AStore store) throws IOException {
		setConnection(Connection.connect(peerAddress, internalHandler, store));
	}

	/**
	 * Signs a value on behalf of this client.
	 *
	 * @param <T>   Type of value to sign
	 * @param value Value to sign
	 * @return
	 */
	public <T extends ACell> SignedData<T> signData(T value) {
		return keyPair.signData(value);
	}

	/**
	 * Gets the Internet address of the currently connected remote
	 *
	 * @return
	 */
	public InetSocketAddress getRemoteAddress() {
		return connection.getRemoteAddress();
	}

	/**
	 * Creates a new account with the gievn public key
	 *
	 * @param publicKey Public key to set for the new account
	 * @return Address of account created
	 * @throws TimeoutException
	 * @throws IOException
	 */
	public synchronized Address createAccount(AccountKey publicKey) throws TimeoutException, IOException {
		Invoke trans = Invoke.create(address, 0, "(create-account 0x" + publicKey.toHexString() + ")");
		Result r = transactSync(trans);
		if (r.isError()) throw new Error("Error creating account: " + r);
		return (Address) r.getValue();
	}

	/**
	 * Checks if this Convex client instance has an open connection.
	 *
	 * @return true if connected, false otherwise
	 */
	public boolean isConnected() {
		Connection c = this.connection;
		return (c != null) && (!c.isClosed());
	}

	/**
	 * Gets the underlying Connection instance for this Client. May be null if not
	 * connected.
	 *
	 * @return Connection instance or null
	 */
	public Connection getConnection() {
		return connection;
	}

	/**
	 * Updates the given transaction to have the next sequence number.
	 *
	 * @param t Any transaction, for which the correct next sequence number is
	 *          desired
	 * @return The updated transaction
	 */
	public synchronized ATransaction applyNextSequence(ATransaction t) {
		if (sequence != null) {
			// if already we know the next sequence number to be applied, set it
			return t.withSequence(++sequence);
		} else {
			return t.withSequence(getIncrementedSequence());
		}
	}

	/**
	 * Submits a transaction to the Convex network, returning a future once the
	 * transaction has been successfully queued. Signs the transaction with the
	 * currently set key pair
	 *
	 * @param transaction Transaction to execute
	 * @return A Future for the result of the transaction
	 * @throws IOException If the connection is broken, or the send buffer is full
	 */
	public synchronized CompletableFuture<Result> transact(ATransaction transaction) throws IOException {
		if (autoSequence || (transaction.getSequence() <= 0)) {
			transaction = applyNextSequence(transaction);
		}
		if (transaction.getAddress() == null) {
			transaction = transaction.withAddress(address);
		}
		SignedData<ATransaction> signed = keyPair.signData(transaction);
		return transact(signed);
	}

	/**
	 * Submits a signed transaction to the Convex network, returning a future once
	 * the transaction has been successfully queued.
	 *
	 * @param signed Signed transaction to execute
	 * @return A Future for the result of the transaction
	 * @throws IOException If the connection is broken, or the send buffer is full
	 */
	public CompletableFuture<Result> transact(SignedData<ATransaction> signed) throws IOException {
		CompletableFuture<Result> cf = new CompletableFuture<Result>();
		synchronized (awaiting) {
			long id = connection.sendTransaction(signed);

			if (id < 0) {
				throw new IOException("Failed to send transaction due to full buffer");
			}

			// Store future for completion by result message
			awaiting.put(id, cf);
		}

		return cf;
	}

	/**
	 * Submits a transfer transaction to the Convex network, returning a future once
	 * the transaction has been successfully queued.
	 *
	 * @param target Destination address for transfer
	 * @param amount Amount of Convex Coins to transfer
	 * @return A Future for the result of the transaction
	 * @throws IOException If the connection is broken, or the send buffer is full
	 */
	public CompletableFuture<Result> transfer(Address target, long amount) throws IOException {
		ATransaction trans = Transfer.create(getAddress(), 0, target, amount);
		return transact(trans);
	}

	/**
	 * Submits a transfer transaction to the Convex network peer, and waits for
	 * confirmation of the result
	 *
	 * @param target Destination address for transfer
	 * @param amount Amount of Convex Coins to transfer
	 * @return Result of the transaction
	 * @throws IOException      If the connection is broken, or the send buffer is
	 *                          full
	 * @throws TimeoutException If the transaction times out
	 */
	public Result transferSync(Address target, long amount) throws IOException, TimeoutException {
		ATransaction trans = Transfer.create(getAddress(), 0, target, amount);
		return transactSync(trans);
	}

	/**
	 * Submits a transaction synchronously to the Convex network, returning a Result
	 *
	 * @param transaction Transaction to execute
	 * @return The result of the transaction
	 * @throws IOException      If the connection is broken
	 * @throws TimeoutException If the attempt to transact with the network is not
	 *                          confirmed within a reasonable time
	 */
	public Result transactSync(SignedData<ATransaction> transaction) throws TimeoutException, IOException {
		return transactSync(transaction, Constants.DEFAULT_CLIENT_TIMEOUT);
	}

	/**
	 * Submits a transaction synchronously to the Convex network, returning a Result
	 *
	 * @param transaction Transaction to execute
	 * @return The result of the transaction
	 * @throws IOException      If the connection is broken
	 * @throws TimeoutException If the attempt to transact with the network is not
	 *                          confirmed within a reasonable time
	 */
	public Result transactSync(ATransaction transaction) throws TimeoutException, IOException {
		return transactSync(transaction, Constants.DEFAULT_CLIENT_TIMEOUT);
	}

	/**
	 * Submits a signed transaction synchronously to the Convex network, returning a
	 * Result
	 *
	 * @param transaction Transaction to execute
	 * @param timeout     Number of milliseconds for timeout
	 * @return The result of the transaction
	 * @throws IOException      If the connection is broken
	 * @throws TimeoutException If the attempt to transact with the network is not
	 *                          confirmed by the specified timeout
	 */
	public Result transactSync(ATransaction transaction, long timeout) throws TimeoutException, IOException {
		// sample time at start of transaction attempt
		long start = Utils.getTimeMillis();

		Future<Result> cf = transact(transaction);

		// adjust timeout if time elapsed to submit transaction
		long now = Utils.getTimeMillis();
		timeout = Math.max(0L, timeout - (now - start));
		try {
			return cf.get(timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException | ExecutionException e) {
			throw new Error("Not possible? Since there is no Thread for the future....", e);
		}
	}

	/**
	 * Submits a signed transaction synchronously to the Convex network, returning a
	 * Result
	 *
	 * @param transaction Transaction to execute
	 * @param timeout     Number of milliseconds for timeout
	 * @return The result of the transaction
	 * @throws IOException      If the connection is broken
	 * @throws TimeoutException If the attempt to transact with the network is not
	 *                          confirmed by the specified timeout
	 */
	public Result transactSync(SignedData<ATransaction> transaction, long timeout)
			throws TimeoutException, IOException {
		// sample time at start of transaction attempt
		long start = Utils.getTimeMillis();

		Future<Result> cf = transact(transaction);

		// adjust timeout if time elapsed to submit transaction
		long now = Utils.getTimeMillis();
		timeout = Math.max(0L, timeout - (now - start));
		try {
			return cf.get(timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException | ExecutionException e) {
			throw new Error("Not possible? Since there is no Thread for the future....", e);
		}
	}

	/**
	 * Submits a query to the Convex network, returning a Future once the query has
	 * been successfully queued.
	 *
	 * @param query Query to execute, as a Form or Op
	 * @return A Future for the result of the query
	 * @throws IOException If the connection is broken, or the send buffer is full
	 */
	public Future<Result> query(ACell query) throws IOException {
		return query(query, getAddress());
	}

	/**
	 * Attempts to acquire a complete persistent data structure for the given hash
	 * from the remote peer. Uses the store configured for the calling thread.
	 *
	 * @param hash Hash of value to acquire.
	 *
	 * @return Future for the cell being acquired
	 */
	public <T extends ACell> Future<T> acquire(Hash hash) {
		return acquire(hash, Stores.current());
	}

	/**
	 * Attempts to acquire a complete persistent data structure for the given hash
	 * from the remote peer. Uses the store provided as a destination.
	 *
	 * @param hash Hash of value to acquire.
	 * @param store Store to acquire the persistent data to.
	 *
	 * @return Future for the Cell being acquired
	 */
	public <T extends ACell> Future<T> acquire(Hash hash, AStore store) {
		CompletableFuture<T> f = new CompletableFuture<T>();
		new Thread(new Runnable() {
			@Override
			public void run() {
				Stores.setCurrent(store); // use store for calling thread
				try {
					Ref<T> ref = store.refForHash(hash);
					HashSet<Hash> missingSet = new HashSet<>();
					while (!f.isDone()) {
						missingSet.clear();

						if (ref == null) {
							missingSet.add(hash);
						} else {
							if (ref.getStatus() >= Ref.PERSISTED) {
								// we have everything!
								f.complete(ref.getValue());
								return;
							}
							ref.findMissing(missingSet);
						}
						for (Hash h : missingSet) {
							// send missing data requests until we fill pipeline
							log.log(LEVEL_AQUIRE, "Request missing: " + h);
							boolean sent = connection.sendMissingData(h);
							if (!sent) {
								log.log(LEVEL_AQUIRE, "Queue full!");
								break;
							}
						}
						// if too low, can send multiple requests, and then block the peer
						Thread.sleep(100);
						ref = store.refForHash(hash);
						if (ref != null) {
							if (ref.getStatus() >= Ref.PERSISTED) {
								// we have everything!
								f.complete(ref.getValue());
								return;
							}
							// maybe complete, but not sure
							try {
								ref = ref.persist();
								f.complete(ref.getValue());
							} catch (MissingDataException e) {
								Hash missing = e.getMissingHash();
								log.log(LEVEL_AQUIRE, "Still missing: " + missing);
								connection.sendMissingData(missing);
							}
						}
					}
				} catch (Throwable t) {
					// catch any errors, probably IO?
					f.completeExceptionally(t);
				}
			}
		}).start();
		return f;
	}

	/**
	 * Submits a status request to the Convex network peer, returning a Future once the
	 * request has been successfully queued.
	 *
	 * @return A Future for the result of the requestStatus
	 * @throws IOException If the connection is broken, or the send buffer is full
	 */
	public Future<Result> requestStatus() throws IOException {
		CompletableFuture<Result> cf = new CompletableFuture<Result>();

		synchronized (awaiting) {
			long id = connection.sendStatusRequest();
			if (id < 0) {
				throw new IOException("Failed to send query due to full buffer");
			}

			// Store future for completion by result message
			awaiting.put(id, cf);
		}

		return cf;
	}

	/**
	 * Request a challenge. This is request is made by any peer that needs to find out
	 * if another peer can be trusted.
	 *
	 * @param data Signed data to send to the peer for the challenge.
	 *
	 * @return A Future for the result of the requestChallenge
	 *
	 * @throws IOException if the connection fails.
	 *
	 */
	public Future<Result> requestChallenge(SignedData<ACell> data) throws IOException {

		CompletableFuture<Result> cf = new CompletableFuture<Result>();

		synchronized (awaiting) {
			long id = connection.sendChallenge(data);
			if (id < 0) {
				throw new IOException("Failed to send challenge due to full buffer");
			}

			// Store future for completion by result message
			awaiting.put(id, cf);
		}

		return cf;
	}


	/**
	 * Submits a query to the Convex network, returning a Future once the query has
	 * been successfully queued.
	 *
	 * @param query   Query to execute, as a Form or Op
	 * @param address Address to use for the query
	 * @return A Future for the result of the query
	 * @throws IOException If the connection is broken, or the send buffer is full
	 */
	public Future<Result> query(ACell query, Address address) throws IOException {
		CompletableFuture<Result> cf = new CompletableFuture<Result>();

		synchronized (awaiting) {
			long id = connection.sendQuery(query, address);
			if (id < 0) {
				throw new IOException("Failed to send query due to full buffer");
			}

			// Store future for completion by result message
			awaiting.put(id, cf);
		}

		return cf;
	}

	/**
	 * Executes a query synchronously and waits for the Result
	 * @param query Query to execute. Map be a form or Op
	 * @return Result of synchronous query
	 * @throws TimeoutException If the synchronous request timed out
	 * @throws IOException In case of network error
	 */
	public Result querySync(ACell query) throws TimeoutException, IOException {
		return querySync(query, getAddress());
	}

	/**
	 * Executes a query synchronously and waits for the Result
	 *
	 * @param timeoutMillis Timeout to wait for query result. Will throw
	 *                      TimeoutException if not received in this time
	 * @param query         Query to execute, as a Form or Op
	 * @return Result of query
	 * @throws TimeoutException If the synchronous request timed out
	 * @throws IOException In case of network error
	 */
	public Result querySync(ACell query, long timeoutMillis) throws IOException, TimeoutException {
		return querySync(query, getAddress(), timeoutMillis);
	}

	/**
	 * Executes a query synchronously and waits for the Result
	 *
	 * @param address Address to use for the query
	 * @param query   Query to execute, as a Form or Op
	 * @return Result of query
	 * @throws TimeoutException If the synchronous request timed out
	 * @throws IOException In case of network error
	 */
	public Result querySync(ACell query, Address address) throws IOException, TimeoutException {
		return querySync(query, address, Constants.DEFAULT_CLIENT_TIMEOUT);
	}

	/**
	 * Executes a query synchronously and waits for the Result
	 *
	 * @param timeoutMillis Timeout to wait for query result. Will throw
	 *                      TimeoutException if not received in this time
	 * @param address       Address to use for the query
	 * @param query         Query to execute, as a Form or Op
	 * @return Result of query
	 * @throws TimeoutException If the synchronous request timed out
	 * @throws IOException In case of network error
	 */
	public Result querySync(ACell query, Address address, long timeoutMillis) throws TimeoutException, IOException {
		Future<Result> cf = query(query, address);

		try {
			return cf.get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException | ExecutionException e) {
			throw new Error("Not possible? Since there is no Thread for the future....", e);
		}
	}

	/**
	 * Returns the current AcountKey for the client using the API.
	 *
	 * @return AcountKey instance
	 */
	public AccountKey getAccountKey() {
		return keyPair.getAccountKey();
	}

	/**
	 * Returns the current Address for the client using the API.
	 *
	 * @return Address instance
	 */
	public Address getAddress() {
		return address;
	}

	/**
	 * Sets the current Connection for this Client
	 *
	 * @param conn Connection value to use
	 */
	private void setConnection(Connection conn) {
		if (this.connection == conn) return;
		close();
		this.connection = conn;
	}

	/**
	 * Disconnects the client from the network.
	 */
	public synchronized void close() {
		Connection c = this.connection;
		if (c != null) {
			c.close();
		}
		connection = null;
		awaiting.clear();
	}

	/**
	 * Determines if this Client is configured to automatically generate sequence
	 * numbers
	 *
	 * @return
	 */
	protected boolean isAutoSequence() {
		return autoSequence;
	}

	/**
	 * Configures auto-generation of sequence numbers
	 *
	 * @param autoSequence true to enable auto-sequencing, false otherwise
	 */
	protected void setAutoSequence(boolean autoSequence) {
		this.autoSequence = autoSequence;
	}

	public Long getBalance(Address address) throws IOException {
		try {
			Future<Result> future = query(Reader.read("(balance " + address.toString() + ")"));
			Result result = future.get(Constants.DEFAULT_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);
			if (result.isError()) throw new Error(result.toString());
			CVMLong bal = (CVMLong) result.getValue();
			return bal.longValue();
		} catch (ExecutionException | InterruptedException | TimeoutException ex) {
			throw new IOException("Unable to query balance", ex);
		}
	}

}
