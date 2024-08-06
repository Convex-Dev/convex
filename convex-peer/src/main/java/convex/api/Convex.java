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
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Keywords;
import convex.core.data.Lists;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.AOp;
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
import convex.net.Message;
import convex.net.ResultConsumer;
import convex.peer.Config;
import convex.peer.Server;

/**
 * Class representing a client API to the Convex network.
 *
 * An instance of the type Convex represents a stateful client connection to the
 * Convex network that can issue transactions both synchronously and
 * asynchronously. This can be used by both peers and JVM-based clients.
 *
 * "I'm doing a (free) operating system (just a hobby, won't be big and
 * professional like gnu)" - Linus Torvalds
 */
@SuppressWarnings("unused")
public abstract class Convex {

	private static final Logger log = LoggerFactory.getLogger(Convex.class.getName());

	protected long timeout = Config.DEFAULT_CLIENT_TIMEOUT;

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
	 * Map of results awaiting completion.
	 */
	protected HashMap<Long, CompletableFuture<Message>> awaiting = new HashMap<>();

	/**
	 * Result Consumer for messages received back from a client connection
	 */
	protected final Consumer<Message> messageHandler = new ResultConsumer() {
		@Override
		protected synchronized void handleResult(long id, Result v) {
			ACell ec=v.getErrorCode();
			
			if (ec!=null) {
				// We probably have a wrong sequence number now. Kill the stored value.
				sequence = null;
			}
		}

		@Override
		public void accept(Message m) {
			// Check if we are waiting for a Result with this ID for this connection
			synchronized (awaiting) {
				CVMLong id=m.getID();
				CompletableFuture<Message> cf = (id==null)?null:awaiting.remove((Long)id.longValue());
				if (cf != null) {
					// log.info("Return message received for message ID: {} with type: {} "+m.toString(), id,m.getType());
					if (cf.complete(m)) return;
				} 
			}
			
			if (delegatedHandler!=null) {
				delegatedHandler.accept(m);
			} else {
				// default handling
				super.accept(m);
			}
		}
	};
	
	private Consumer<Message> delegatedHandler=null;


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

	public void setNextSequence(long nextSequence) {
		this.sequence = nextSequence - 1L;
	}

	/**
	 * Sets a handler for messages that are received but not otherwise processed (transaction/query results will
	 * be relayed instead to the appropriate handler )
	 * @param handler Handler for received messaged
	 */
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
	 * @throws IOException If an IO error occurs
	 * @throws TimeoutException If the request times out
	 */
	public long getSequence() throws IOException, TimeoutException {
		if (sequence == null) {
			sequence=lookupSequence(getAddress());
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
		if (Cells.equals(getAddress(), addr)) return getSequence();
		return lookupSequence(addr);
	}
	
	/**
	 * Look up the sequence number for an account
	 * @param origin Account for which to check sequence
	 * @return Sequence number of account
	 * @throws TimeoutException if query times out
	 * @throws IOException in case of IO error
	 */
	public long lookupSequence(Address origin) throws IOException, TimeoutException {
		AOp<ACell> code= Special.forSymbol(Symbols.STAR_SEQUENCE);
		Result r= querySync(code,origin);
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
			if (!Cells.equals(trans.getOrigin(),address)) return;
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
	 * @throws TimeoutException 
	 * @throws IOException 
	 */
	private synchronized long getNextSequence(ATransaction t) throws IOException, TimeoutException {
		if (sequence != null) {
			// if already we know the next sequence number to be applied, set it
			return sequence+1;
		} else {
			return getSequence()+1;
		}
	}

	/**
	 * Submits a transaction to the Convex network, returning a future once the
	 * transaction has been successfully queued. Signs the transaction with the
	 * currently set key pair.
	 *
	 * Should be thread safe as long as multiple clients do not attempt to submit
	 * transactions for the same account concurrently.
	 * 
	 * May block briefly if the send buffer is full.
	 *
	 * @param transaction Transaction to execute
	 * @return A Future for the result of the transaction
	 * @throws IOException If an IO Exception occurs (most likely the connection is broken)
	 * @throws TimeoutException  In case of timeout
	 */
	public final synchronized CompletableFuture<Result> transact(ATransaction transaction) throws IOException, TimeoutException {
		SignedData<ATransaction> signed = prepareTransaction(transaction);
		CompletableFuture<Result> r= transact(signed);
		return r;
	}

	/**
	 * Prepares a transaction for network submission
	 * - Sets origin account if needed
	 * - Sets sequence number (if autosequencing is enabled)
	 * - Signs transaction with current key pair
	 *
	 * @param transaction Transaction to prepare
	 * @return Signed transaction ready to submit
	 * @throws IOException If an IO Exception occurs (most likely the connection is broken)
	 * @throws TimeoutException In case of timeout
	 */
	public SignedData<ATransaction> prepareTransaction(ATransaction transaction) throws TimeoutException, IOException {
		Address origin=transaction.getOrigin();
		if (origin == null) {
			origin=address;
			transaction = transaction.withOrigin(origin);
		}
		
		final long originalSeq=transaction.getSequence(); // zero or negative means autosequence
		long seq=originalSeq;
		
		if (autoSequence||(originalSeq<=0)) {		
			if (seq <= 0) {		
				// apply sequence if using expected address
				if (Cells.equals(origin, address)) {
					seq = getNextSequence(transaction);
				}
			}
			
			// If local, update sequence number based on latest consensus state
			Server s=getLocalServer();
			if (s!=null) {
				State state=s.getPeer().getConsensusState();
				AccountStatus as=state.getAccount(origin);
				if (as!=null) {
					long expected=as.getSequence()+1;
					if (expected>seq) {
						seq=expected;
					}
				}
			}
		}
		
		if (seq<=0) seq=lookupSequence(origin);
		
		// Update sequence if any change required
		if (seq!=originalSeq) {
			transaction=transaction.withSequence(seq);
		}
		
		// Store updated sequence number, ready for next transaction
		if (Cells.equals(origin, address)) { 
			this.sequence=seq;
		}		
		
		SignedData<ATransaction> signed = keyPair.signData(transaction);
		return signed;
	}

	/**
	 * Executes a transaction, compiling the given source code as an Invoke.
	 *
	 * @param code Code to execute
	 * @return A Future for the result of the transaction
	 * @throws IOException If the connection is broken, or the send buffer is full
	 * @throws TimeoutException In case of timeout
	 */
	public synchronized CompletableFuture<Result> transact(String code) throws IOException, TimeoutException {
		return transact((ACell)Reader.read(code));
	}
	
	/**
	 * Executes a transaction, compiling the given source code as an Invoke.
	 *
	 * @param code Code to execute
	 * @return A Future for the result of the transaction
	 * @throws IOException If the connection is broken, or the send buffer is full
	 * @throws TimeoutException In case of timeout
	 */
	public synchronized CompletableFuture<Result> transact(ACell code) throws IOException, TimeoutException {
		ATransaction trans = Invoke.create(getAddress(), ATransaction.UNKNOWN_SEQUENCE, code);
		return transact(trans);
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
		ATransaction trans = Invoke.create(getAddress(), ATransaction.UNKNOWN_SEQUENCE, code);
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
	 * @throws IOException If the connection is broken or send buffer is full
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
	 * @throws TimeoutException In case of timeout
	 */
	public CompletableFuture<Result> transfer(Address target, long amount) throws IOException, TimeoutException {
		ATransaction trans = Transfer.create(getAddress(), ATransaction.UNKNOWN_SEQUENCE, target, amount);
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
	public synchronized Result transactSync(ATransaction transaction, long timeout) throws TimeoutException, IOException {
		// sample time at start of transaction attempt
		long start = Utils.getTimeMillis();
		Result result;

		Future<Result> cf = transact(transaction);

		// adjust timeout if time elapsed to submit transaction
		long now = Utils.getTimeMillis();
		timeout = Math.max(0L, timeout - (now - start));
		try {
			result = cf.get(timeout, TimeUnit.MILLISECONDS);
			if (result.getErrorCode()!=null) {
				// On error, clear cached sequence, it is possibly invalid
				sequence=null;
			}
			return result;
		} catch (InterruptedException e) {
			throw Utils.sneakyThrow(e);
		} catch (ExecutionException e) {
			return Result.fromException(e.getCause());
		}
	}

	/**
	 * Submits a signed transaction synchronously to the Convex network, returning a
	 * Result
	 *
	 * @param transaction Transaction to execute
	 * @param timeout     Number of milliseconds for timeout
	 * @return The Result of the transaction, if received
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
			return result;
		} catch (InterruptedException e) {
			throw Utils.sneakyThrow(e);
		} catch (ExecutionException e) {
			throw Utils.sneakyThrow(e);
			// return Result.fromException(e);
		} finally {
			cf.cancel(true);
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
	 * Attempts to resolve a CNS name
	 *
	 * @param cnsName CNS name to resolve
	 * @return A Future for the resolved CNS value
	 * @throws IOException In case of IO failure
	 */
	public CompletableFuture<ACell> resolve(String cnsName) throws IOException {
		ACell form = buildCodeForm("(import "+cnsName+")");
		return query(form).thenApply(r->{
			if (r.isError()) throw new RuntimeException("Resolve failed "+r);
			return r.getValue();
		});
	}

	/**
	 * Attempts to asynchronously acquire a complete persistent data structure for the given hash
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
			throw Utils.sneakyThrow(e);
		} finally {
			// in case the future is still running?
			statusFuture.cancel(true);
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
	protected CompletableFuture<Result> awaitResult(long id, long timeout) {
		// TODO: timeout parameter, maybe allow 0 timeout for never fail?
		CompletableFuture<Message> cf = new CompletableFuture<Message>();
		if (timeout>0) {
			cf=cf.orTimeout(timeout, TimeUnit.MILLISECONDS);
		}
		cf=cf.whenComplete((m,e)->{
			synchronized(awaiting) {
				awaiting.remove(id);
			}
		});
		awaiting.put(id, cf);
		CompletableFuture<Result> cr=cf.thenApply(m->{
			Result r=m.toResult();
			
			if (r.getErrorCode()!=null) {
				// clear sequence if something went wrong. It is probably invalid now....
				sequence=null;
			}
			return r;
		});
		return cr;
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
	 * @throws IOException In case of IO problem (connection is broken, send buffer full)
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
	protected Result querySync(ACell query, Address address, long timeoutMillis) throws TimeoutException, IOException {
		Future<Result> cf = query(query, address);
		Result result;
		try {
			result = cf.get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			throw Utils.sneakyThrow(e);
		} catch (ExecutionException e) {
			return Result.fromException(e.getCause());
		} finally {
			cf.cancel(true);
		}
		return result;
	}

	/**
	 * Returns the current AccountKey for the client using the API.
	 *
	 * @return AcountKey instance, or null if no keypair is set
	 */
	public AccountKey getAccountKey() {
		if (keyPair==null) return null;
		return keyPair.getAccountKey();
	}

	/**
	 * Returns the current AccountKey for the specified address. Performs a sync query
	 *
	 * @return AcountKey instance, or null if unavailable
	 */
	public AccountKey getAccountKey(Address a) {
		if (a==null) return null;
		
		try {
			Result r=querySync(Reader.read("(:key (account "+a+"))"));
			if (r.isError()) return null;
			ABlob b=RT.ensureBlob(r.getValue());
			return AccountKey.create(b);
		} catch (Exception e) {
			return null;
		}
		
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

	/**
	 * Query the balance for the current account
	 * @return Long balance in Convex coins, 
	 * @throws IOException If balance query fails due to IO
	 */
	public Long getBalance() throws IOException {
		Address a = getAddress();
		if (a==null) throw new IllegalStateException("No address set for balance query");
		return getBalance(a);
	}
	
	public Long getBalance(Address address) throws IOException {
		try {
			ACell code;
			if (Utils.equals(this.address, address)) {
				code=Special.forSymbol(Symbols.STAR_BALANCE);
			} else {
				code=Lists.of(Symbols.BALANCE, address);
			}
			Future<Result> future = query(code);
			Result result = future.get(timeout, TimeUnit.MILLISECONDS);
			if (result.isError()) {
				return null;
			}
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
	 * Connect to a local Server, with no address and keypair set
	 * 
	 * @param server  Server to connect to
	 * @return New Client Connection
	 */
	public static ConvexLocal connect(Server server) {
		return ConvexLocal.create(server, null, null);
	}

	/**
	 * Gets the consensus state from the remote Peer
	 * 
	 * @return Future for consensus state
	 * @throws TimeoutException If initial status request times out
	 */
	public abstract CompletableFuture<State> acquireState() throws TimeoutException;
	
	/**
	 * Sets the default timeout for this Convex client instance.
	 * @param timeout timeout in milliseconds
	 */
	public void setTimeout(long timeout) {
		this.timeout=timeout;
	}
	
	@Override 
	public abstract String toString();

	/**
	 * Get the keypair for this Convex connection. 
	 * @return Keypair or null if not set
	 */
	public AKeyPair getKeyPair() {
		return keyPair;
	}

	/**
	 * Gets the local Server instance, or null if not a local connection
	 * @return Server instance (or null)
	 */
	public abstract Server getLocalServer();

	/**
	 * Gets the remote address for this Convex client instance
	 * @return Socket address
	 */
	public abstract InetSocketAddress getHostAddress();




}
