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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.Keywords;
import convex.core.data.Lists;
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
import convex.core.State;
import convex.net.ResultConsumer;
import convex.net.message.Message;
import convex.peer.Server;

/**
 * Class representing a client API to the Convex network.
 *
 * An Object of the type Convex represents a stateful client connection to the
 * Convex network that can issue transactions both synchronously and
 * asynchronously. This can be used by both peers and JVM-based clients.
 *
 * "I'm doing a (free) operating system (just a hobby, won't be big and
 * professional like gnu)" - Linus Torvalds
 */
@SuppressWarnings("unused")
public abstract class Convex {

	private static final Logger log = LoggerFactory.getLogger(Convex.class.getName());

	protected long timeout = Constants.DEFAULT_CLIENT_TIMEOUT;

	/**
	 * Key pair for this Client
	 */
	protected AKeyPair keyPair;

	/**
	 * Current Address for this Client
	 */
	protected Address address;

	/**
	 * Determines if auto-sequencing should be attempted. Default to true.
	 */
	private boolean autoSequence = true;

	/**
	 * Sequence number for this client, or null if not yet known. Used to number new
	 * transactions if not otherwise specified.
	 */
	protected Long sequence = null;

	/**
	 * Map of results awaiting completion. May be pending missing data.
	 */
	protected HashMap<Long, CompletableFuture<Result>> awaiting = new HashMap<>();

	protected final Consumer<Message> internalHandler = new ResultConsumer() {
		@Override
		protected synchronized void handleResult(long id, Result v) {

			if ((v != null) && (ErrorCodes.SEQUENCE.equals(v.getErrorCode()))) {
				// We probably got a wrong sequence number. Kill the stored value.
				sequence = null;
			}

			// TODO: maybe extract method?
			synchronized (awaiting) {
				CompletableFuture<Result> cf = awaiting.get(id);
				if (cf != null) {
					awaiting.remove(id);
					cf.complete(v);
					log.debug("Completed Result received for message ID: {}", id);
				} else {
					log.debug("Ignored Result received for unexpected message ID: {}", id);
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
					log.warn("Exception thrown in user-supplied handler function: {}", t);
				}
			}
		}
	};

	private Consumer<Message> delegatedHandler = null;

	protected Convex(Address address, AKeyPair keyPair) {
		this.keyPair = keyPair;
		this.address = address;
	}

	/**
	 * Creates an anonymous connection to a Peer, suitable for queries
	 * 
	 * @param hostAddress Address of Peer
	 * @return New Convex client instance
	 * @throws IOException      If IO Error occurs
	 * @throws TimeoutException If connection attempt times out
	 */
	public static ConvexRemote connect(InetSocketAddress hostAddress) throws IOException, TimeoutException {
		return connect(hostAddress, (Address) null, (AKeyPair) null);
	}

	/**
	 * Create a Convex client by connecting to the specified Peer using the given
	 * key pair
	 *
	 * @param peerAddress Address of Peer
	 * @param address     Address of Account to use for Client
	 * @param keyPair     Key pair to use for client transactions
	 * @return New Convex client instance
	 * @throws IOException      If connection fails due to IO error
	 * @throws TimeoutException If connection attempt times out
	 */
	public static ConvexRemote connect(InetSocketAddress peerAddress, Address address, AKeyPair keyPair)
			throws IOException, TimeoutException {
		return Convex.connect(peerAddress, address, keyPair, Stores.current());
	}

	/**
	 * Create a remote connection to a Convex Server in the same JVM.
	 * 
	 * @param server Server instance to connect to.
	 * @return Convex client instance
	 * @throws IOException      If connection fails due to IO error
	 * @throws TimeoutException If connection attempt times out
	 */
	public static ConvexRemote connectRemote(Server server) throws IOException, TimeoutException {
		return connect(server.getHostAddress(), server.getPeerController(), server.getKeyPair());
	}

	/**
	 * Create a Convex client by connecting to the specified Peer using the given
	 * key pair and using a given store
	 *
	 * @param peerAddress Address of Peer
	 * @param address     Address of Account to use for Client
	 * @param keyPair     Key pair to use for client transactions
	 * @param store       Store to use for this connection
	 * @return New Convex client instance
	 * @throws IOException      If connection fails due to IO error
	 * @throws TimeoutException If connection attempt times out
	 */
	public static ConvexRemote connect(InetSocketAddress peerAddress, Address address, AKeyPair keyPair, AStore store)
			throws IOException, TimeoutException {
		ConvexRemote convex = new ConvexRemote(address, keyPair);
		convex.connectToPeer(peerAddress, store);
		return convex;
	}

	/**
	 * Sets the Address for this connection. This will be used for subsequent
	 * transactions and queries. User should also set a new keypair if a different
	 * keypair is required for the new Address.
	 *
	 * @param address Address to use
	 */
	public synchronized void setAddress(Address address) {
		if (this.address == address)
			return;
		this.address = address;
		// clear sequence, since we don't know the new account sequence number yet
		sequence = null;
	}

	/**
	 * Sets the Address and Keypair for this connection. This will be used for
	 * subsequent transactions and queries.
	 *
	 * @param address Address to use
	 * @param kp      Keypair to use for the given Address
	 */
	public synchronized void setAddress(Address address, AKeyPair kp) {
		setAddress(address);
		setKeyPair(kp);
	}

	public synchronized void setKeyPair(AKeyPair kp) {
		this.keyPair = kp;
	}

	/**
	 * Gets the next sequence number for this Client, which should be used for
	 * building new signed transactions.
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
	 * Will attempt to acquire the sequence number from the network if not known.
	 * 
	 * The next valid sequence number will be one higher than the result.
	 *
	 * @return Sequence number as a Long value (zero or positive)
	 */
	public long getSequence() {
		if (sequence == null) {
			try {
				Future<Result> f = query(Special.forSymbol(Symbols.STAR_SEQUENCE));
				Result r = f.get();
				if (r.isError())
					throw new Error("Error querying *sequence*: " + r.getErrorCode() + " " + r.getValue());
				ACell result = r.getValue();
				if (!(result instanceof CVMLong))
					throw new Error("*sequence* query did not return Long, got: " + result);
				sequence = RT.jvm(result);
			} catch (IOException | InterruptedException | ExecutionException e) {
				throw new Error("Error trying to get sequence number", e);
			}
		}
		return sequence;
	}
	
	/**
	 * Gets the current sequence number for an account, which is the sequence
	 * number of the last transaction observed for the Account.
	 * Will attempt to acquire the sequence number from the network if not known.
	 * 
	 * @param addr Address for which to query the sequence number
	 *
	 * @return Sequence number as a Long value (zero or positive)
	 * @throws IOException If an IO error occurs
	 * @throws TimeoutException If the request times out
	 */
	public long getSequence(Address addr) throws TimeoutException, IOException {
		if (Utils.equals(getAddress(), addr)) return getSequence();
		ACell code= Lists.of(Keywords.SEQUENCE, Lists.of(Symbols.ACCOUNT, addr));
		Result r= querySync(code);
		if (r.isError()) throw new RuntimeException("Error trying to get sequence number: "+r);
		ACell rv=r.getValue();
		if (!(rv instanceof CVMLong)) throw new RuntimeException("Unexpected sequence result type: "+Utils.getClassName(rv));
		long seq=((CVMLong)rv).longValue();
		return seq;
	}
	
	/**
	 * Called after a transaction is submitted to update sequence (if possible)
	 * @param value
	 */
	protected void maybeUpdateSequence(SignedData<ATransaction> signed) {
		try {
			ATransaction trans=signed.getValue();
			if (!isAutoSequence()) return;
			if (!Utils.equals(trans.getOrigin(),address)) return;
			Long seq=this.sequence;
			if (seq==null) return;
			seq++;
			if (seq==trans.getSequence()) sequence=seq;
		} catch (Exception e) {
			// do nothing. Shouldn't happen except in some adversarial test cases.
		}
	}

	/**
	 * Signs a value on behalf of this client, using the currently assigned keypair.
	 *
	 * @param <T>   Type of value to sign
	 * @param value Value to sign
	 * @return SignedData instance
	 */
	public <T extends ACell> SignedData<T> signData(T value) {
		return keyPair.signData(value);
	}

	/**
	 * Creates a new account with the given public key
	 *
	 * @param publicKey Public key to set for the new account
	 * @return Address of account created
	 * @throws TimeoutException If attempt times out
	 * @throws IOException      If IO error occurs
	 */
	public Address createAccountSync(AccountKey publicKey) throws TimeoutException, IOException {
		Invoke trans = Invoke.create(address, 0, Lists.of(Symbols.CREATE_ACCOUNT, publicKey));
		Result r = transactSync(trans);
		if (r.isError())
			throw new Error("Error creating account: " + r.getErrorCode() + " " + r.getValue());
		return (Address) r.getValue();
	}

	/**
	 * Creates a new account with the given public key
	 *
	 * @param publicKey Public key to set for the new account
	 * @return Address of account created
	 * @throws TimeoutException If attempt times out
	 * @throws IOException      If IO error occurs
	 */
	public CompletableFuture<Address> createAccount(AccountKey publicKey) throws TimeoutException, IOException {
		Invoke trans = Invoke.create(address, 0, Lists.of(Symbols.CREATE_ACCOUNT, publicKey));
		CompletableFuture<Result> fr = transact(trans);
		return fr.thenApply(r -> r.getValue());
	}

	/**
	 * Checks if this Convex client instance has an open connection.
	 *
	 * @return true if connected, false otherwise
	 */
	public abstract boolean isConnected();

	/**
	 * Updates the given transaction to have the next sequence number.
	 *
	 * @param t Any transaction, for which the correct next sequence number is
	 *          desired
	 * @return The updated transaction
	 */
	private synchronized ATransaction applyNextSequence(ATransaction t) {
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
	 * Should be thread safe as long as multiple clients do not attempt to submit
	 * transactions for the same account concurrently.
	 *
	 * @param transaction Transaction to execute
	 * @return A Future for the result of the transaction
	 * @throws IOException If the connection is broken, or the send buffer is full
	 */
	public synchronized CompletableFuture<Result> transact(ATransaction transaction) throws IOException {
		if (transaction.getOrigin() == null) {
			transaction = transaction.withOrigin(address);
		}
		if (autoSequence && (transaction.getSequence() <= 0)) {
			// apply sequence if using expected address
			if (Utils.equals(transaction.getOrigin(), address)) {
				transaction = applyNextSequence(transaction);
			} else {
				// ignore??
			}
		}
		SignedData<ATransaction> signed = keyPair.signData(transaction);
		CompletableFuture<Result> r= transact(signed);
		return r;
	}

	/**
	 * Executes a transaction, compiling the given source code as an Invoke.
	 *
	 * @param code Code to execute
	 * @return A Future for the result of the transaction
	 * @throws IOException If the connection is broken, or the send buffer is full
	 */
	public synchronized CompletableFuture<Result> transact(String code) throws IOException {
		ATransaction trans = buildTransaction(code);
		return transact(trans);
	}

	private ATransaction buildTransaction(String code) {
		ACell form = buildCodeForm(code);
		return Invoke.create(getAddress(), getIncrementedSequence(), form);
	}

	private ACell buildCodeForm(String code) {
		AList<ACell> forms = Reader.readAll(code);
		ACell form;
		if (forms.count() == 1) {
			form = forms.get(0);
		} else {
			form = forms.cons(Symbols.DO);
		}
		return form;
	}

	/**
	 * Executes a transaction, compiling the given source code as an Invoke.
	 *
	 * @param code Code to execute
	 * @return A Future for the result of the transaction
	 * @throws IOException      If the connection is broken, or the send buffer is
	 *                          full
	 * @throws TimeoutException If the transaction times out
	 */
	public synchronized Result transactSync(String code) throws IOException, TimeoutException {
		ATransaction trans = buildTransaction(code);
		return transactSync(trans);
	}

	/**
	 * Submits a signed transaction to the Convex network, returning a Future once
	 * the transaction has been successfully queued.
	 * 
	 * Updates cached sequence number on best effort basis.
	 *
	 * @param signed Signed transaction to execute
	 * @return A Future for the result of the transaction
	 * @throws IOException If the connection is broken
	 */
	public abstract CompletableFuture<Result> transact(SignedData<ATransaction> signed) throws IOException;

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
		return transactSync(transaction, timeout);
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
		return transactSync(transaction, timeout);
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
		Result result;

		Future<Result> cf = transact(transaction);

		// adjust timeout if time elapsed to submit transaction
		long now = Utils.getTimeMillis();
		timeout = Math.max(0L, timeout - (now - start));
		try {
			result = cf.get(timeout, TimeUnit.MILLISECONDS);
			
		} catch (InterruptedException | ExecutionException e) {
			throw new Error("Not possible? Since there is no Thread for the future....", e);
		}
		return result;
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
		Result result;

		Future<Result> cf = transact(transaction);

		// adjust timeout if time elapsed to submit transaction
		long now = Utils.getTimeMillis();
		timeout = Math.max(0L, timeout - (now - start));
		try {
			result = cf.get(timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException | ExecutionException e) {
			throw new Error("Not possible? Since there is no Thread for the future....", e);
		}
		return result;
	}

	/**
	 * Submits a query to the Convex network, returning a Future once the query has
	 * been successfully queued.
	 *
	 * @param query Query to execute, as a Form or Op
	 * @return A Future for the result of the query
	 * @throws IOException If the connection is broken, or the send buffer is full
	 */
	public CompletableFuture<Result> query(ACell query) throws IOException {
		return query(query, getAddress());
	}

	/**
	 * Submits a query to the Convex network, returning a Future once the query has
	 * been successfully queued.
	 *
	 * @param query Query to execute, as String containing one or more forms
	 * @return A Future for the result of the query
	 * @throws IOException If the connection is broken, or the send buffer is full
	 */
	public CompletableFuture<Result> query(String query) throws IOException {
		ACell form = buildCodeForm(query);
		return query(form, getAddress());
	}

	/**
	 * Attempts to acquire a complete persistent data structure for the given hash
	 * from the remote peer. Uses the current store configured for the calling
	 * thread.
	 *
	 * @param hash Hash of value to acquire.
	 *
	 * @return Future for the cell being acquired
	 */
	public <T extends ACell> CompletableFuture<T> acquire(Hash hash) {
		return acquire(hash, Stores.current());
	}

	/**
	 * Attempts to acquire a complete persistent data structure for the given hash
	 * from the connected peer. Uses the store provided as a destination.
	 *
	 * @param hash  Hash of value to acquire.
	 * @param store Store to acquire the persistent data to.
	 *
	 * @return Future for the Cell being acquired. May fail exceptionally or timeout
	 *         if the given data cannot be acquired (most likely missing from the
	 *         peer's store)
	 */
	public abstract <T extends ACell> CompletableFuture<T> acquire(Hash hash, AStore store);

	/**
	 * Request status using a sync operation. This request will automatically get
	 * any missing data with the status request
	 *
	 * @param timeoutMillis Milliseconds to wait for request timeout
	 * @return Status Vector from target Peer
	 *
	 * @throws IOException      If an IO Error occurs
	 * @throws TimeoutException If operation times out
	 *
	 */
	public Result requestStatusSync(long timeoutMillis) throws IOException, TimeoutException {
		Future<Result> statusFuture = requestStatus();
		try {
			return statusFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException | ExecutionException e) {
			throw new Error("Unable to get network status ", e);
		}
	}

	/**
	 * Submits a status request to the Convex network peer, returning a Future once
	 * the request has been successfully queued.
	 *
	 * @return A Future for the result of the requestStatus
	 */
	public abstract CompletableFuture<Result> requestStatus();

	/**
	 * Method to start waiting for a complete result. Should be called with lock on
	 * `awaiting` map to prevent risk of missing results before it is called.
	 * 
	 * @param id ID of result message to await
	 * @return
	 */
	protected CompletableFuture<Result> awaitResult(long id) {
		CompletableFuture<Result> cf = new CompletableFuture<Result>();
		awaiting.put(id, cf);
		return cf;
	}

	/**
	 * Request a challenge. This is request is made by any peer that needs to find
	 * out if another peer can be trusted.
	 *
	 * @param data Signed data to send to the peer for the challenge.
	 *
	 * @return A Future for the result of the requestChallenge
	 *
	 * @throws IOException if the connection fails.
	 *
	 */
	public abstract CompletableFuture<Result> requestChallenge(SignedData<ACell> data) throws IOException;

	/**
	 * Submits a query to the Convex network, returning a Future once the query has
	 * been successfully queued.
	 *
	 * @param query   Query to execute, as a Form or Op
	 * @param address Address to use for the query
	 * @return A Future for the result of the query
	 * @throws IOException If the connection is broken, or the send buffer is full
	 */
	public abstract CompletableFuture<Result> query(ACell query, Address address) throws IOException;

	/**
	 * Executes a query synchronously and waits for the Result
	 * 
	 * @param query Query to execute. Map be a form or Op
	 * @return Result of synchronous query
	 * @throws TimeoutException If the synchronous request timed out
	 * @throws IOException      In case of network error
	 */
	public Result querySync(ACell query) throws TimeoutException, IOException {
		return querySync(query, getAddress());
	}

	/**
	 * Executes a query synchronously and waits for the Result
	 * 
	 * @param query Query to execute, as a String that contains one or more readable
	 *              forms. Multiple forms will be wrapped in a `do` block
	 * @return Result of synchronous query
	 * @throws TimeoutException If the synchronous request timed out
	 * @throws IOException      In case of network error
	 */
	public Result querySync(String query) throws TimeoutException, IOException {
		ACell form = buildCodeForm(query);
		return querySync(form, getAddress());
	}

	/**
	 * Executes a query synchronously and waits for the Result
	 *
	 * @param timeoutMillis Timeout to wait for query result. Will throw
	 *                      TimeoutException if not received in this time
	 * @param query         Query to execute, as a Form or Op
	 * @return Result of query
	 * @throws TimeoutException If the synchronous request timed out
	 * @throws IOException      In case of network error
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
	 * @throws IOException      In case of network error
	 */
	public Result querySync(ACell query, Address address) throws IOException, TimeoutException {
		return querySync(query, address, timeout);
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
	 * @throws IOException      In case of network error
	 */
	public Result querySync(ACell query, Address address, long timeoutMillis) throws TimeoutException, IOException {
		Future<Result> cf = query(query, address);
		Result result;
		try {
			result = cf.get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException | ExecutionException e) {
			throw new Error("Not possible? Since there is no Thread for the future....", e);
		}
		return result;
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
	 * Disconnects the client from the network, releasing any connection resources.
	 */
	public abstract void close();

	@Override
	public void finalize() {
		close();
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
			Future<Result> future = query(Lists.of(Symbols.BALANCE, address));
			Result result = future.get(timeout, TimeUnit.MILLISECONDS);
			if (result.isError())
				throw new Error(result.toString());
			CVMLong bal = (CVMLong) result.getValue();
			return bal.longValue();
		} catch (ExecutionException | InterruptedException | TimeoutException ex) {
			throw new IOException("Unable to query balance", ex);
		}
	}

	/**
	 * Connect to a local Server, using given address and keypair
	 * 
	 * @param server  Server to connect to
	 * @param address Address to use
	 * @param keyPair Keypair to use
	 * @return New Client Connection
	 */
	public static ConvexLocal connect(Server server, Address address, AKeyPair keyPair) {
		return ConvexLocal.create(server, address, keyPair);
	}

	/**
	 * Gets the consensus state from the remote Peer
	 * 
	 * @return Future for consensus state
	 * @throws TimeoutException If initial status request times out
	 */
	public abstract CompletableFuture<State> acquireState() throws TimeoutException;
	
	@Override 
	public abstract String toString();

}
