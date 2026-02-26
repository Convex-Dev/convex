package convex.peer;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Constants;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.cpos.Belief;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.util.Shutdown;
import convex.core.util.Utils;
import convex.net.AServer;
import convex.net.impl.netty.NettyServer;
import convex.net.impl.nio.NIOServer;


/**
 * A self-contained Peer Server that can be launched with a config.
 *
 * <p>The Server is the top-level coordinator for a Convex peer. It accepts inbound
 * messages, dispatches them to specialised components, and orchestrates the peer
 * lifecycle (launch, sync, persistence, shutdown).
 *
 * <p>Each major responsibility is handled by a dedicated threaded component:
 * <ul>
 *   <li>{@link TransactionHandler} — validates and batches client transactions into blocks</li>
 *   <li>{@link QueryHandler} — executes read-only queries against the latest consensus state</li>
 *   <li>{@link BeliefPropagator} — merges incoming beliefs and broadcasts updates to peers</li>
 *   <li>{@link CVMExecutor} — applies confirmed blocks to advance the CVM state machine</li>
 *   <li>{@link ConnectionManager} — maintains authenticated outbound connections to peers</li>
 * </ul>
 *
 * <p>"Programming is a science dressed up as art, because most of us don't
 * understand the physics of software and it's rarely, if ever, taught. The
 * physics of software is not algorithms, data structures, languages, and
 * abstractions. These are just tools we make, use, and throw away. The real
 * physics of software is the physics of people. Specifically, it's about our
 * limitations when it comes to complexity and our desire to work together to
 * solve large problems in pieces. This is the science of programming: make
 * building blocks that people can understand and use easily, and people will
 * work together to solve the very largest problems." ― Pieter Hintjens
 */
public class Server implements Closeable {
	public static final int DEFAULT_PORT = Constants.DEFAULT_PEER_PORT;
	
	static final Logger log = LoggerFactory.getLogger(Server.class.getName());

	private Consumer<Message> messageReceiveObserver=null;

	/**
	 * Blocking message consumer for the NIO path and tests. Delegates to
	 * {@link #deliverMessage} and, if a retry predicate is returned, blocks the
	 * caller's thread until the message is accepted or times out.
	 */
	Consumer<Message> receiveAction = m->{
		Predicate<Message> retry = deliverMessage(m);
		if (retry != null) {
			if (!retry.test(m)) {
				Result r=Result.create(m.getID(), Strings.SERVER_LOADED, ErrorCodes.LOAD).withSource(SourceCodes.PEER);
				try {
					m.returnResult(r);
				} catch (Exception e) {
					// best effort
				}
			}
		}
	};

	/**
	 * Manages authenticated outbound connections to other peers in the network.
	 */
	protected final ConnectionManager manager = new ConnectionManager(this);

	/**
	 * Merges incoming beliefs from peers and broadcasts consensus updates across the network.
	 */
	protected final BeliefPropagator propagator=new BeliefPropagator(this);

	/**
	 * Validates client transactions, batches them into blocks, and feeds them into consensus.
	 */
	protected final TransactionHandler transactionHandler=new TransactionHandler(this);

	/**
	 * Applies confirmed blocks to advance the CVM state machine and deliver results to clients.
	 */
	protected final CVMExecutor executor=new CVMExecutor(this);

	/**
	 * Executes read-only queries against the latest consensus state, independently of transaction processing.
	 */
	protected final QueryHandler queryHandler=new QueryHandler(this);

	/**
	 * Verifies untrusted inbound connections via server-initiated challenge/response.
	 */
	private final InboundVerifier inboundVerifier = new InboundVerifier(this);

	/**
	 * Pre-allocated retry predicates for backpressure. These are returned by
	 * processMessage when a queue is full, and block until space is available.
	 * Zero allocation on the reject path — method references are bound once.
	 */
	private final Predicate<Message> txnRetry = transactionHandler::offerTransactionBlocking;
	private final Predicate<Message> queryRetry = queryHandler::offerQueryBlocking;

	/**
	 * Store to use for all threads associated with this server instance
	 */
	private final AStore store;

	/**
	 * Configuration
	 */
	private final HashMap<Keyword, Object> config;

	/**
	 * Network server (Netty or NIO) that accepts inbound client and peer connections.
	 */
	private AServer nio;

	@SuppressWarnings("deprecation")
	private Server(HashMap<Keyword, Object> config) throws ConfigException {
		this.config = config;
		
		// Critical to ensure we have the store set up before anything else. Stuff might break badly otherwise!
		this.store = Config.ensureStore(config);
		
		if (Config.USE_NETTY_SERVER) {
			this.nio=NettyServer.create(this);
		} else {
			this.nio= NIOServer.create(this);
		}
		
	}

	private Peer establishPeer() throws ConfigException, LaunchException, InterruptedException {
		log.debug("Establishing Peer with store: {}",store);
		AKeyPair keyPair = Config.ensurePeerKey(config);
		if (keyPair==null) {
			log.warn("No keypair provided for Server, defaulting to generated keypair for testing purposes");
			keyPair=AKeyPair.generate();
			config.put(Keywords.KEYPAIR,keyPair);
			log.warn("Generated keypair with public key: "+keyPair.getAccountKey());
		}
		
		try {
			Object source=getConfig().get(Keywords.SOURCE);
			if (Utils.bool(source)) {
				try {
					Convex c=Convex.connect(source);
					c.setStore(getStore());
					return syncPeer(keyPair,c);
				} catch (TimeoutException e) {
					throw new LaunchException("Timeout trying to connect to remote peer");
				} catch (IllegalArgumentException e) {
					throw new LaunchException("Bad :SOURCE for peer launch",e);
				} catch (Exception e ) {
					// something else failed, probably an IOException
					throw new LaunchException("Failed to sync with remote peer host at: "+source,e);
				}
			} 
			
			if (Utils.bool(getConfig().get(Keywords.RESTORE))) {
				ACell rk=RT.cvm(config.get(Keywords.ROOT_KEY));
				if (rk==null) rk=keyPair.getAccountKey();
	
				Peer peer = Peer.restorePeer(store, keyPair, rk);
				if (peer != null) {
					log.info("Restored Peer with root data hash: {}",store.getRootHash());
					return peer;
				}
			} 
			
			// No sync or restored state, so use passed state
			State genesisState = (State) config.get(Keywords.STATE);
			if (genesisState!=null) {
				log.info("Defaulting to standard Peer startup with genesis state: "+genesisState.getHash());
			} else {
				AccountKey peerKey=keyPair.getAccountKey();
				genesisState=Init.createState(List.of(peerKey));
				log.info("Created new genesis state: "+genesisState.getHash()+ " with initial peer: "+peerKey);
			}
			return Peer.createGenesisPeer(keyPair,genesisState);

		} catch (IOException e) {
			throw new LaunchException("IO Exception while establishing peer: "+e,e);
		}
	}

	public Peer syncPeer(AKeyPair keyPair, Convex convex) throws LaunchException, InterruptedException {
		// Peer sync case
		try {
			log.info("Attempting Peer Sync with: "+convex);
			long timeout = establishTimeout();
			
			// Sync status and genesis state
			Result result = convex.requestStatusSync(timeout);
			AMap<Keyword,ACell> status = API.ensureStatusMap(result.getValue());
			if ((result.isError()) || status == null) {
				throw new LaunchException("Bad status message from remote Peer: "+result);
			}
			
			Hash beliefHash=RT.ensureHash(status.get(Keywords.BELIEF));
			AccountKey remotePeerKey=RT.ensureAccountKey(status.get(Keywords.PEER));
			Hash genesisHash=RT.ensureHash(status.get(Keywords.GENESIS));
			Hash stateHash=RT.ensureHash(status.get(Keywords.STATE));
			
			if (genesisHash==null) {
				throw new LaunchException("Remote peer did not provide genesis hash");
			}
			
			log.debug("Attempting to sync remote state: "+stateHash + " on network: "+genesisHash);
			State genF=(State) convex.acquire(genesisHash).get(timeout,TimeUnit.MILLISECONDS);
			log.debug("Retrieved Genesis State: "+genesisHash);
			
			// Belief acquisition
			log.debug("Attempting to obtain peer Belief: "+beliefHash);
			Belief belF=null;
			long timeElapsed=0;
			while (belF==null) {
				try {
					belF=(Belief) convex.acquire(beliefHash).get(timeout,TimeUnit.MILLISECONDS);
				} catch (TimeoutException te) {
					timeElapsed+=timeout;
					log.info("Still waiting for Belief sync after "+timeElapsed/1000+"s");
				}
			}
			log.info("Retrieved Peer Belief: "+beliefHash+ " with memory size: "+belF.getMemorySize());
	
			// Add the new connection since it seems good
			getConnectionManager().addConnection(remotePeerKey,convex);
			
			SignedData<Order> peerOrder=belF.getOrders().get(remotePeerKey);

			
			if (peerOrder!=null) {
				// We got an order from remote peer, so assume correct
				SignedData<Order> newOrder=keyPair.signData(peerOrder.getValue());
				belF=belF.withOrders(belF.getOrders().assoc(keyPair.getAccountKey(),newOrder));
			} else {
				// No order, so start with an empty Ordering
				SignedData<Order> newOrder=keyPair.signData(Order.create());
				belF=belF.withOrders(belF.getOrders().assoc(keyPair.getAccountKey(),newOrder));
			}

			Peer peer=Peer.create(keyPair, genF, belF);
			return peer;
		} catch (ExecutionException | InvalidDataException e) {
			throw new LaunchException("Erring while trying to sync peer",e);
		}  catch (TimeoutException e) {
			throw new LaunchException("Timeout attempting to sync peer",e);
		}
	}

	private long establishTimeout() {
		Object maybeTimeout=getConfig().get(Keywords.TIMEOUT);
		if (maybeTimeout==null) return Config.PEER_SYNC_TIMEOUT;
		return Utils.toInt(maybeTimeout);
	}

	/**
	 * Creates a new (unlaunched) Server with a given config.
	 *
	 * @param config Server configuration map. Will be defensively copied.
	 *
	 * @return New Server instance
	 * @throws ConfigException If Peer configuration failed, possible multiple causes
	 */
	public static Server create(HashMap<Keyword, Object> config) throws ConfigException {
		return new Server(new HashMap<>(config));
	}

	private void observeMessageReceived(Message m) {
		Consumer<Message> obs=messageReceiveObserver;
		if (obs!=null) {
			obs.accept(m);
		}
	}
	
	public void setMessageReceiveObserver(Consumer<Message> observer) {
		this.messageReceiveObserver=observer;
	}

	/**
	 * Gets the current Belief held by this Peer
	 *
	 * @return Current Belief
	 */
	public Belief getBelief() {
		return getPeer().getBelief();
	}

	/**
	 * Gets the current Peer data structure for this {@link Server}.
	 *
	 * @return Current Peer data
	 */
	public Peer getPeer() {
		return executor.getPeer();
	}

	/**
	 * Gets the desired host name for this Peer
	 * @return Hostname String
	 */
	public String getHostname() {
		return (String) config.get(Keywords.URL);
	}

	/**
	 * Launch the Peer Server, including all main server threads
	 * @throws InterruptedException 
	 */
	public synchronized void launch() throws LaunchException, InterruptedException {
		if (isRunning) return; // in case of double launch
		isRunning=true;
		try {
			// Establish Peer state
			Peer peer = establishPeer();

			// Ensure Peer is stored in executor and initially persisted prior to launch
			executor.setPeer(peer);
			executor.persistPeerData();

			HashMap<Keyword, Object> config = getConfig();


			if (config.containsKey(Keywords.RECALC)) try {
				Object o=config.get(Keywords.RECALC);
				if (o!=null) {
					Long pos=Utils.toLong(o);
					executor.recalcState(pos);
				}
			} catch (Exception e) {
				throw new LaunchException("Launch failed to recalculate state: "+e,e);
			}

			Object p = config.get(Keywords.PORT);
			Integer port = (p == null) ? null : Utils.toInt(p);

			nio.setPort(port);
			nio.launch();
			port = nio.getPort(); // Get the actual port (may be auto-allocated)

			// set running status now, so that loops don't immediately terminate
			isRunning = true;

			// Close server on shutdown, should be before Etch stores in priority
			Shutdown.addHook(Shutdown.SERVER, this::close);

			// Start threaded components
			manager.start();
			queryHandler.start();
			propagator.start();
			transactionHandler.start();
			executor.start();

			goLive();
			log.info( "Peer server started on port "+nio.getPort()+" with peer key: {}",getPeerKey());
		} catch (ConfigException e) {
			throw new LaunchException("Launch failed due to config problem: "+e,e);
		} catch (IOException e) {
			throw new LaunchException("Launch failed due to IO Error: "+e,e);
		}
	}

	private void goLive() {
		isLive=true;
	}

	/**
	 * Dispatches a decoded inbound message to the appropriate handler.
	 *
	 * <p>Client messages (transactions and queries) are offered to bounded queues. Protocol
	 * messages (beliefs, challenges, status) are handled inline since they are lightweight.
	 *
	 * <p>Non-blocking on the fast path: a single {@code queue.offer()} and return. If the
	 * target queue is full, returns a pre-allocated retry predicate instead of an error —
	 * the caller (typically {@link convex.net.impl.netty.NettyInboundHandler}) parks the
	 * channel and lets the predicate block on a virtual thread until space is available.
	 *
	 * <p>SECURITY: Must anticipate malicious or malformed messages.
	 *
	 * @param m Message to process (already decoded)
	 * @return null if accepted, or a retry Predicate that blocks until delivered or timeout
	 */
	public Predicate<Message> processMessage(Message m) {
		try {
			MessageType type = m.getType();
			switch (type) {
			case TRANSACT:
				if (transactionHandler.offerTransaction(m)) return null;
				return txnRetry;

			case QUERY: case DATA_REQUEST:
				if (queryHandler.offerQuery(m)) return null;
				return queryRetry;

			// Protocol messages — always accepted, handled inline
			case BELIEF:
				processBelief(m);
				return null;
			case CHALLENGE:
				processChallenge(m);
				return null;
			case GOODBYE:
				processClose(m);
				return null;
			case STATUS:
				processStatus(m);
				return null;
			case PING:
				processPing(m);
				return null;
			case COMMAND:
				return null;
			case RESULT:
				// Check if this is a response to a server-initiated verification
				if (inboundVerifier.handleResult(m)) return null;
				returnError(m,ErrorCodes.UNEXPECTED,Strings.UNEXPECTED_RESULT);
				return null;
			default:
				returnError(m,ErrorCodes.FORMAT,Strings.UNRECOGNISED_MESSAGE_TYPE);
				return null;
			}
		} catch (MissingDataException e) {
			Hash missingHash = e.getMissingHash();
			log.info("Missing data: {} in message of type {}", missingHash, m.getType());
			try {
				m.returnResult(Result.error(ErrorCodes.MISSING, Strings.create("Missing data: "+missingHash)).withSource(SourceCodes.PEER));
			} catch (Exception e2) {
				// best effort -- some message types don't have return handlers
			}
			return null;
		} catch (Exception e) {
			log.warn("Unexpected error processing peer message",e);
			try {
				m.returnResult(Result.fromException(e).withID(m.getID()));
			} catch (Exception e2) {
				// best effort
			}
			return null;
		}
	}

	/**
	 * Delivers an inbound message: decodes payload, observes, and dispatches.
	 * Returns null if accepted, or a blocking retry predicate if the queue was full.
	 *
	 * This is the primary entry point for both Netty and ConvexLocal message delivery.
	 *
	 * @param m Message to deliver
	 * @return null if accepted, or a retry Predicate that blocks until delivered or timeout
	 */
	public Predicate<Message> deliverMessage(Message m) {
		try {
			m.getPayload(getStore());
		} catch (Exception e) {
			log.debug("Failed to decode message: {}", e.getMessage());
			try {
				ACell id = m.getRequestID();
				m.returnMessage(Message.createResult(Result.fromException(e).withID(id)));
			} catch (Exception e2) {
				// best effort -- connection may be bad
			}
			return null;
		}
		observeMessageReceived(m);
		return processMessage(m);
	}

	/**
	 * Gets the current consensus state for this Peer Server
	 * @return Current consensus state
	 */
	public State getState() {
		return getPeer().getConsensusState();
	}


	/**
	 * Called by a remote peer to close connections to the remote peer.
	 *
	 */
	protected void processClose(Message m) {
		m.closeConnection();
	}

	/**
	 * Best-effort error return to the sender of a message.
	 * Silently ignores failures (message may not have a return handler or ID).
	 */
	private boolean returnError(Message m, Keyword errorCode, AString message) {
		try {
			return m.returnResult(Result.error(errorCode, message).withSource(SourceCodes.PEER));
		} catch (Exception e) {
			// best effort — some message types don't have return handlers
			return false;
		}
	}

	/**
	 * Gets the number of belief broadcasts made by this Peer
	 * @return Count of broadcasts from this Server instance
	 */
	public long getBroadcastCount() {
		return propagator.getBeliefBroadcastCount();
	}
	
	/**
	 * Gets the number of beliefs received by this Peer
	 * @return Count of the beliefs received by this Server instance
	 */
	public long getBeliefReceivedCount() {
		return propagator.beliefReceivedCount;
	}



	/**
	 * Gets the Peer controller Address
	 * @return Peer controller Address, or null if peer is not registered
	 */
	public Address getPeerController() {
		return getPeer().getController();
	}

	/**
	 * Adds an event to the inbound server event queue.
	 * @param event Signed event to add to inbound event queue
	 * @return True if Belief was successfully queued, false otherwise
	 */
	public boolean queueBelief(Message event) {
		boolean offered=propagator.queueBelief(event);
		return offered;
	}
	
	protected void processPing(Message m) {
		ACell id = m.getRequestID();
		if (id == null) return;
		m.returnResult(Result.create(id, CVMLong.create(Utils.getCurrentTimestamp())));
	}

	protected void processStatus(Message m) {
		// We can ignore payload
		ACell reply = getStatusData();
		
		// TODO for 0.9.0 ACell reply = getStatusMap();
		
		Result r=Result.create(m.getID(), reply);
		m.returnResult(r);
	}

	/**
	 * Gets the status vector for the Peer
	 * 0 = latest belief hash
	 * 1 = states vector hash
	 * 2 = genesis state hash
	 * 3 = peer key
	 * 4 = consensus state hash
	 * 5 = consensus point
	 * 6 = proposal point
	 * 7 = ordering length
	 * 8 = consensus point vector
	 * @return Status vector
	 */
	public AVector<ACell> getStatusData() {
		Peer peer=getPeer();
		Belief belief=peer.getBelief();
		
		State state=peer.getConsensusState();
		
		Hash beliefHash=belief.getHash();
		Hash stateHash=state.getHash();
		Hash genesisHash=peer.getNetworkID();
		AccountKey peerKey=peer.getPeerKey();
		Hash consensusHash=state.getHash();
		
		Order order=belief.getOrder(peerKey);
		CVMLong cp = CVMLong.create(order.getConsensusPoint()) ;
		CVMLong pp = CVMLong.create(order.getProposalPoint()) ;
		CVMLong op = CVMLong.create(order.getBlockCount()) ;
		AVector<CVMLong> cps = Vectors.of(Utils.toObjectArray(order.getConsensusPoints())) ;

		AVector<ACell> reply=Vectors.of(beliefHash,stateHash,genesisHash,peerKey,consensusHash, cp,pp,op,cps);
		assert(reply.count()==Config.STATUS_COUNT);
		return reply;
	}
	
	public AMap<Keyword,ACell> getStatusMap() {
		return Maps.zipMap(API.STATUS_KEYS,getStatusData());
	}

	private void processChallenge(Message m) {
		manager.processChallenge(m, getPeer());
		// If they're verifying us, also try to verify them
		AConnection conn = m.getConnection();
		if (conn != null) inboundVerifier.maybeStart(conn);
	}



	/**
	 * Process an incoming message that represents a Belief.
	 * Trusted connections go to the main queue; untrusted go to a small
	 * best-effort queue and trigger server-initiated verification.
	 * @param m Belief message to process
	 */
	protected void processBelief(Message m) {
		AConnection conn=m.getConnection();
		if (conn==null || conn.isTrusted()) {
			// Trusted or local (ConvexLocal) — main queue
			if (!propagator.queueBelief(m)) {
				log.warn("Incoming belief queue full");
			}
		} else {
			// Untrusted inbound — best-effort queue, trigger verification
			propagator.queueUntrustedBelief(m);
			inboundVerifier.maybeStart(conn);
		}
	}

	/**
	 * Gets the port that this Server is currently accepting connections on
	 * @return Port number
	 */
	public Integer getPort() {
		return nio.getPort();
	}

	/** Returns the number of active inbound client connections. */
	public int getInboundConnectionCount() {
		return nio.getClientConnectionCount();
	}

	/**
	 * Writes the Peer data to the configured store.
	 * 
	 * Note: Does not flush buffers to disk. 
	 *
	 * This will overwrite any previously persisted peer data.
	 * @return Updated Peer value with persisted data
	 * @throws IOException In case of any IO Error
	 */
	@SuppressWarnings("unchecked")
	public Peer persistPeerData() throws IOException {
		Peer peer=getPeer();
		AMap<Keyword,ACell> peerData = peer.toData();

		// Set up root key for Peer persistence. Default is Peer Account Key
		ACell rk=RT.cvm(config.get(Keywords.ROOT_KEY));
		if (rk==null) rk=peer.getPeerKey();
		ACell rootKey=rk;

		Ref<AMap<ACell,ACell>> rootRef = store.refForHash(store.getRootHash());
		AMap<ACell,ACell> currentRootData = (rootRef == null)? Maps.empty() : rootRef.getValue();
		AMap<ACell,ACell> newRootData = currentRootData.assoc(rootKey, peerData);

		newRootData=store.setRootData(newRootData).getValue();

		// ensure specific values are persisted, might be needed for lookup
		store.storeTopRef(peer.getGenesisState().getRef(), Ref.PERSISTED, null);
		store.storeTopRef(peer.getBelief().getRef(), Ref.PERSISTED, null);

		peerData=(AMap<Keyword, ACell>) newRootData.get(rootKey);
		log.debug( "Stored peer data with hash: {}", peerData.getHash().toHexString());
		return Peer.fromData(getKeyPair(), peerData);
	}

	/**
	 * Future to complete with timestamp at time of shutdown
	 */
	private CompletableFuture<Long> shutdownFuture=new CompletableFuture<Long>(); 
	
	@Override
	public void close() {
		
		if (!isRunning) return; // already shut down
		log.debug("Peer shutdown starting for "+getPeerKey());
		isLive=false;
		isRunning = false;

		// Close manager, we don't want any management actions during shutdown!
		manager.close();

		// Shut down propagator, no point sending any more Beliefs
		propagator.close();
		
		queryHandler.close();
		transactionHandler.close();
		executor.close();
		
		Peer peer=getPeer();
		// persist peer state if necessary
		if ((peer != null) && !Boolean.FALSE.equals(getConfig().get(Keywords.PERSIST))) {
			try {
				persistPeerData();
			} catch (IOException e) {
				log.warn("Unable to persist Peer data in "+store,e);
			}
		}

		nio.close();
		// Note we don't do store.close(); because we don't own the store.
		log.info("Peer shutdown complete for "+getPeerKey());
		shutdownFuture.complete(Utils.getCurrentTimestamp());
	}

	/**
	 * Gets the host address for this Server (including port), or null if closed
	 *
	 * @return Host Address
	 */
	public InetSocketAddress getHostAddress() {
		return nio.getHostAddress();
	}

	/**
	 * Returns the Keypair for this peer server
	 *
	 * SECURITY: Be careful with this!
	 * @return Key pair for Peer
	 */
	public AKeyPair getKeyPair() {
		return getPeer().getKeyPair();
	}

	/**
	 * Gets the public key of the peer account
	 *
	 * @return AccountKey of this Peer
	 */
	public AccountKey getPeerKey() {
		AKeyPair kp = getKeyPair();
		if (kp == null) return null;
		return kp.getAccountKey();
	}
	
	/**
	 * Gets the peer controller key for the Server, if available
	 *
	 * @return Keypair for controller
	 */

	public AKeyPair getControllerKey() {
		AKeyPair kp = getKeyPair();
		if (kp == null) return null;
		try {
			AccountStatus as=getPeer().getConsensusState().getAccount(getPeerController());
			if (Cells.equals(as.getAccountKey(), kp.getAccountKey())) return kp;
		} catch (Exception e) {
			log.warn("Unexpected exception trying to get controller key",e);
		}
		return null;
	}

	/**
	 * Gets the Store configured for this Server. A server must consistently use the
	 * same store instance for all Server threads, as values may be shared.
	 *
	 * @return Store instance
	 */
	public AStore getStore() {
		return store;
	}

	public ConnectionManager getConnectionManager() {
		return manager;
	}

	/** Number of inbound connections successfully verified since startup. */
	public long getInboundVerifiedCount() {
		return inboundVerifier.getVerifiedCount();
	}

	/** Number of inbound verifications currently in progress. */
	public int getInboundPendingVerifications() {
		return inboundVerifier.getPendingCount();
	}

	public HashMap<Keyword, Object> getConfig() {
		return config;
	}

	/**
	 * Gets the action to perform for an incoming client message
	 * @return Message consumer
	 */
	public Consumer<Message> getReceiveAction() {
		return receiveAction;
	}

	/**
	 * Sets the desired host name for this Server
	 * @param string Desired host name String, e.g. "my-domain.com:12345"
	 */
	public void setHostname(String string) {
		config.put(Keywords.URL, string);
	}


	/**
	 * Flag for a running server. Setting to false will terminate server threads.
	 */
	private volatile boolean isRunning = false;
	
	/**
	 * Flag for a live server. Live Server has synced with at least one other peer
	 */
	private volatile boolean isLive = false;
	
	/**
	 * Checks is the server is Live, i.e. currently syncing successfully with network
	 * @return True if live, false otherwise
	 */
	public boolean isLive() {
		return isLive;
	}
	
	/**
	 * Checks if the Server threads are running
	 * @return True if running, false otherwise
	 */
	public boolean isRunning() {
		return isRunning;
	}

	public TransactionHandler getTransactionHandler() {
		return transactionHandler;
	}

	public BeliefPropagator getBeliefPropagator() {
		return propagator;
	}
 
	/**
	 * Triggers CVM Executor Belief update
	 * @param belief New Belief
	 */
	public void updateBelief(Belief belief) {
		executor.queueUpdate(belief);
	}

	public CVMExecutor getCVMExecutor() {
		return executor;
	}

	public QueryHandler getQueryProcessor() {
		return queryHandler;
	}

	/**
	 * Shut down the Server, as gracefully as possible.
	 */
	public void shutdown()  {
		try {
			AKeyPair kp= getKeyPair();
			AccountKey key=kp.getAccountKey();
			Convex convex=Convex.connect(this, getPeerController(),kp);
			Result r=convex.transactSync("(set-peer-stake "+key+" 0)");
			if (r.isError()) {
				log.warn("Unable to remove Peer stake: "+r);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			isLive=false;
			close();
		}
	}

	public void waitForShutdown() throws InterruptedException {
		while (isRunning()&&!Thread.currentThread().isInterrupted()) {
			Thread.sleep(1000);
		}
	}

	public static Server fromPeerData(AKeyPair kp, AMap<Keyword,ACell> peerData) throws LaunchException, ConfigException, InterruptedException {
		HashMap<Keyword, Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR, kp);
		return API.launchPeer(config);
	}


}
