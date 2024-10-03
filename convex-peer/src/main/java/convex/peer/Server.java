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
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.Counters;
import convex.core.util.Shutdown;
import convex.core.util.Utils;
import convex.net.Message;
import convex.net.MessageType;
import convex.net.NIOServer;


/**
 * A self contained Peer Server that can be launched with a config.
 * 
 * The primary role for the Server is to respond to incoming messages and maintain
 * network consensus.
 *
 * Components contained within the Server handle specific tasks, e.g:
 * - Client transaction handling
 * - CPoS Belief merges
 * - Belief Propagation
 * - CVM Execution
 *
 * "Programming is a science dressed up as art, because most of us don't
 * understand the physics of software and it's rarely, if ever, taught. The
 * physics of software is not algorithms, data structures, languages, and
 * abstractions. These are just tools we make, use, and throw away. The real
 * physics of software is the physics of people. Specifically, it's about our
 * limitations when it comes to complexity and our desire to work together to
 * solve large problems in pieces. This is the science of programming: make
 * building blocks that people can understand and use easily, and people will
 * work together to solve the very largest problems." ― Pieter Hintjens
 *
 */
public class Server implements Closeable {
	public static final int DEFAULT_PORT = Constants.DEFAULT_PEER_PORT;
	
	static final Logger log = LoggerFactory.getLogger(Server.class.getName());

	private Consumer<Message> messageReceiveObserver=null;

	/**
	 * Message Consumer that handles received client messages received by this peer
	 * Called on NIO thread: should never block
	 */
	Consumer<Message> receiveAction = m->{
		observeMessageReceived(m);
		processMessage(m);
	};

	/**
	 * Connection manager instance.
	 */
	protected final ConnectionManager manager = new ConnectionManager(this);
	
	/**
	 * Connection manager instance.
	 */
	protected final BeliefPropagator propagator=new BeliefPropagator(this);
	
	/**
	 * Transaction handler instance.
	 */
	protected final TransactionHandler transactionHandler=new TransactionHandler(this);
	
	/**
	 * Transaction handler instance.
	 */
	protected final CVMExecutor executor=new CVMExecutor(this);

	/**
	 * Query handler instance.
	 */
	protected final QueryHandler queryHandler=new QueryHandler(this);

	/**
	 * Store to use for all threads associated with this server instance
	 */
	private final AStore store;

	/**
	 * Configuration
	 */

	private final HashMap<Keyword, Object> config;

	/**
	 * NIO Server instance
	 */
	private NIOServer nio = NIOServer.create(this);

	private Server(HashMap<Keyword, Object> config) throws ConfigException {
		this.config = config;
		
		// Critical to ensure we have the store set up before anything else. Stuff might break badly otherwise!
		this.store = Config.ensureStore(config);
	}

	// This doesn't actually do anything useful? Do we need this?
//	/**
//	 * Establish the controller Account for this Peer.
//	 */
//	private void establishController() {
//		Peer peer=getPeer();
//		Address controlAddress=RT.toAddress(getConfig().get(Keywords.CONTROLLER));
//		if (controlAddress==null) {
//			controlAddress=peer.getController();
//			if (controlAddress==null) {
//				throw new IllegalStateException("Peer Controller account does not exist for Peer Key: "+peer.getPeerKey());
//			}
//		}
//		AccountStatus as=peer.getConsensusState().getAccount(controlAddress);
//		if (as==null) {
//			log.warn("Peer Controller Account does not currently exist (perhaps pending sync?): "+controlAddress);	
//		} else if (!Utils.equals(as.getAccountKey(),getKeyPair().getAccountKey())) {
//			// TODO: not a problem?
//			log.warn("Server keypair does not match keypair for control account: "+controlAddress);
//		}
//	}

	private Peer establishPeer() throws ConfigException, LaunchException, InterruptedException {
		log.debug("Establishing Peer with store: {}",Stores.current());
		AKeyPair keyPair = Config.ensurePeerKey(config);
		if (keyPair==null) {
			log.warn("No keypair provided for Server, deafulting to generated keypair for testing purposes");
			keyPair=AKeyPair.generate();
			config.put(Keywords.KEYPAIR,keyPair);
			log.warn("Generated keypair with public key: "+keyPair.getAccountKey());
		}
		
		try {
			Object source=getConfig().get(Keywords.SOURCE);
			if (Utils.bool(source)) {
				try {
					return syncPeer(keyPair,Convex.connect(source));
				} catch (TimeoutException e) {
					throw new LaunchException("Timeout trying to connect to remote peer");
				} catch (IllegalArgumentException e) {
					throw new LaunchException("Bad :SOURCE for peer launch",e);
				}
			} else if (Utils.bool(getConfig().get(Keywords.RESTORE))) {
				ACell rk=RT.cvm(config.get(Keywords.ROOT_KEY));
				if (rk==null) rk=keyPair.getAccountKey();
	
				Peer peer = Peer.restorePeer(store, keyPair, rk);
				if (peer != null) {
					log.info("Restored Peer with root data hash: {}",store.getRootHash());
					return peer;
				}
			}
			State genesisState = (State) config.get(Keywords.STATE);
			if (genesisState!=null) {
				log.debug("Defaulting to standard Peer startup with genesis state: "+genesisState.getHash());
			} else {
				AccountKey peerKey=keyPair.getAccountKey();
				genesisState=Init.createState(List.of(peerKey));
				log.debug("Created new genesis state: "+genesisState.getHash()+ " with initial peer: "+peerKey);
			}
			return Peer.createGenesisPeer(keyPair,genesisState);
		} catch (IOException e) {
			throw new LaunchException("IO Exception while establishing peer",e);
		}
	}

	public Peer syncPeer(AKeyPair keyPair, Convex convex) throws LaunchException, InterruptedException {
		// Peer sync case
		try {
			log.info("Attempting Peer Sync with: "+convex);
			long timeout = establishTimeout();
			
			// Sync status and genesis state
			Result result = convex.requestStatusSync(timeout);
			AVector<ACell> status = result.getValue();
			if (status == null || status.count()!=Config.STATUS_COUNT) {
				throw new Error("Bad status message from remote Peer");
			}
			Hash beliefHash=RT.ensureHash(status.get(0));
			AccountKey remoteKey=RT.ensureAccountKey(status.get(3));
			Hash genesisHash=RT.ensureHash(status.get(2));
			Hash stateHash=RT.ensureHash(status.get(4));
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
	
			convex.close();
			SignedData<Order> peerOrder=belF.getOrders().get(remoteKey);
			if (peerOrder!=null) {
				SignedData<Order> newOrder=keyPair.signData(peerOrder.getValue());
				belF=belF.withOrders(belF.getOrders().assoc(keyPair.getAccountKey(),newOrder));
			} else {
				throw new LaunchException("Remote peer Belief missing it's own Order? Who to trust?");
			}
			// System.out.println(Lists.of(peerOrder.getValue().getConsensusPoints()));

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
	public void launch() throws LaunchException, InterruptedException {
		AStore savedStore=Stores.current();
		try {
			Stores.setCurrent(store);
			
			// Establish Peer state
			Peer peer = establishPeer();

			// Ensure Peer is stored in executor and initially persisted prior to launch
			executor.setPeer(peer);
			executor.persistPeerData();

			HashMap<Keyword, Object> config = getConfig();

			Object p = config.get(Keywords.PORT);
			Integer port = (p == null) ? null : Utils.toInt(p);

			nio.launch((String)config.get(Keywords.BIND_ADDRESS), port);
			port = nio.getPort(); // Get the actual port (may be auto-allocated)

			// set running status now, so that loops don't immediately terminate
			isRunning = true;
			
			// Close server on shutdown, should be before Etch stores in priority
			Shutdown.addHook(Shutdown.SERVER, ()->close());
			
			// Start threaded components
			manager.start();
			queryHandler.start();
			propagator.start();
			transactionHandler.start();
			executor.start();

	
			goLive();
			log.info( "Peer server started on port "+nio.getPort()+" with peer key: {}",getPeerKey());
		} catch (ConfigException e) {
			throw new LaunchException("Launch failed due to config problem",e);
		} catch (IOException e) {
			throw new LaunchException("Launch failed due to IO Error",e);
		} finally {
			Stores.setCurrent(savedStore);
		}
	}

	private void goLive() {
		isLive=true;
	}

	/**
	 * Process a message received from a peer or client. We know at this point that the
	 * message decoded successfully, not much else.....
	 * 
	 * SECURITY: Should anticipate malicious messages
	 *
	 * Runs on receiver thread, so we want to offload to a queue ASAP
	 *
	 * @param m
	 */
	protected void processMessage(Message m) {
		MessageType type = m.getType();
		AStore tempStore=Stores.current();
		try {
			Stores.setCurrent(this.store);
			switch (type) {
			case BELIEF:
				processBelief(m);
				break;
			case CHALLENGE:
				processChallenge(m);
				break;
			case RESPONSE:
				processResponse(m);
				break;
			case COMMAND:
				break;
			case DATA:
				processData(m);
				break;
			case REQUEST_DATA:
				processQuery(m); // goes on Query handler
				break;
			case QUERY:
				processQuery(m);
				break;
			case RESULT:
				break;
			case TRANSACT:
				processTransact(m);
				break;
			case GOODBYE:
				processClose(m);
				break;
			case STATUS:
				processStatus(m);
				break;
			default:
				Result r=Result.create(m.getID(), Strings.create("Bad Message Type: "+type), ErrorCodes.ARGUMENT);
				m.returnResult(r);
				break;
			}
		} catch (MissingDataException e) {
			Hash missingHash = e.getMissingHash();
			log.trace("Missing data: {} in message of type {}" , missingHash,type);
		} finally {
			Stores.setCurrent(tempStore);
		}
	}

	/**
	 * Respond to a request for missing data, on a best-efforts basis. Requests for
	 * missing data we do not hold are ignored.
	 *
	 * @param m
	 * @throws BadFormatException
	 */
	protected void handleDataRequest(Message m)  {
		// payload for a missing data request should be a valid Hash
		try {
			Message response=m.makeDataResponse(store);
			boolean sent = m.returnMessage(response);
			if (!sent) {
				log.trace("Can't send data request response due to full buffer");
			}
		} catch (BadFormatException e) {
			log.warn("Unable to deliver missing data due badly formatted DATA_REQUEST: {}", m);
		} catch (RuntimeException e) {
			log.warn("Unable to deliver missing data due to exception:", e);
		}
	}

	protected void processTransact(Message m) {
		boolean queued=transactionHandler.offerTransaction(m);
		
		if (queued) {
			// log.info("transaction queued");
		} else {
			// Failed to queue transaction
			Result r=Result.create(m.getID(), Strings.SERVER_LOADED, ErrorCodes.LOAD).withSource(SourceCodes.PEER);
			m.returnResult(r);
		} 
	}

	/**
	 * Called by a remote peer to close connections to the remote peer.
	 *
	 */
	protected void processClose(Message m) {
		m.closeConnection();
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
	 * @return Peer controller Address
	 */
	public Address getPeerController() {
		return getPeer().getController();
	}

	/**
	 * Adds an event to the inbound server event queue. May block.
	 * @param event Signed event to add to inbound event queue
	 * @return True if Belief was successfullly queued, false otherwise
	 */
	public boolean queueBelief(Message event) {
		boolean offered=propagator.queueBelief(event);
		return offered;
	}
	
	protected void processStatus(Message m) {
		// We can ignore payload
		AVector<ACell> reply = getStatusVector();
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
	public AVector<ACell> getStatusVector() {
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

	private void processChallenge(Message m) {
		manager.processChallenge(m, getPeer());
	}

	protected void processResponse(Message m) {
		manager.processResponse(m, getPeer());
	}

	protected void processQuery(Message m) {
		boolean queued= queryHandler.offerQuery(m);
		if (!queued) {
			Result r=Result.create(m.getID(), Strings.SERVER_LOADED, ErrorCodes.LOAD);
			m.returnResult(r);
		} 
	}
	
	private void processData(Message m) {
		ACell payload;
		try {
			payload = m.getPayload();
			Counters.peerDataReceived++;
			
			// Note: partial messages are handled in Connection now
			Ref<?> r = Ref.get(payload);
			if (r.isEmbedded()) {
				log.warn("DATA with embedded value: "+payload);
				return;
			}
			r = r.persistShallow();
		} catch (BadFormatException | IOException e) {
			log.debug("Error processing data: "+e.getMessage());
			m.closeConnection();
			return;
		}

	}

	/**
	 * Process an incoming message that represents a Belief
	 *
	 * @param m
	 */
	protected void processBelief(Message m) {
		if (!propagator.queueBelief(m)) {
			log.warn("Incoming belief queue full");
		}
	}

	/**
	 * Gets the port that this Server is currently accepting connections on
	 * @return Port number
	 */
	public int getPort() {
		return nio.getPort();
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
		AStore tempStore = Stores.current();
		try {
			Peer peer=getPeer();
			Stores.setCurrent(store);
			AMap<Keyword,ACell> peerData = peer.toData();

			// Set up root key for Peer persistence. Default is Peer Account Key
			ACell rk=RT.cvm(config.get(Keywords.ROOT_KEY));
			if (rk==null) rk=peer.getPeerKey();
			ACell rootKey=rk;

			Ref<AMap<ACell,ACell>> rootRef = store.refForHash(store.getRootHash());
			AMap<ACell,ACell> currentRootData = (rootRef == null)? Maps.empty() : rootRef.getValue();
			AMap<ACell,ACell> newRootData = currentRootData.assoc(rootKey, peerData);

			newRootData=store.setRootData(newRootData).getValue();
			peerData=(AMap<Keyword, ACell>) newRootData.get(rootKey);
			log.debug( "Stored peer data with hash: {}", peerData.getHash().toHexString());
			return Peer.fromData(getKeyPair(), peerData);
		}  finally {
			Stores.setCurrent(tempStore);
		}
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
				log.warn("Unable to persist Peer data: ",e);
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
	private volatile boolean isRunning = true;
	
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
	 * @throws TimeoutException If shutdown attempt times out
	 * @throws IOException  In case of IO Error
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
			Thread.sleep(400);
		}
	}
}
