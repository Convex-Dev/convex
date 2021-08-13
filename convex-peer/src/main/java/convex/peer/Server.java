package convex.peer;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Belief;
import convex.core.Block;
import convex.core.BlockResult;
import convex.core.Constants;
import convex.core.ErrorCodes;
import convex.core.Peer;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.PeerStatus;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.init.Init;
import convex.core.lang.Context;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.lang.impl.AExceptional;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Shutdown;
import convex.core.util.Utils;
import convex.net.Connection;
import convex.net.Message;
import convex.net.MessageType;
import convex.net.NIOServer;


/**
 * A self contained server that can be launched with a config.
 *
 * Server creates the following threads:
 * - A ReceiverThread that processes message from the Server's receive Queue
 * - An UpdateThread that handles Belief updates and transaction processing
 * - A ConnectionManager thread, via the ConnectionManager
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
	public static final int DEFAULT_PORT = 18888;

	private static final int RECEIVE_QUEUE_SIZE = 10000;

	private static final int EVENT_QUEUE_SIZE = 1000;

	// Maximum Pause for each iteration of Server update loop.
	private static final long SERVER_UPDATE_PAUSE = 5L;

	static final Logger log = LoggerFactory.getLogger(Server.class.getName());

	// private static final Level LEVEL_MESSAGE = Level.FINER;

	/**
	 * Queue for received messages to be processed by this Peer Server
	 */
	private BlockingQueue<Message> receiveQueue = new ArrayBlockingQueue<Message>(RECEIVE_QUEUE_SIZE);

	/**
	 * Queue for received events (Beliefs, Transactions) to be processed
	 */
	private BlockingQueue<SignedData<?>> eventQueue = new ArrayBlockingQueue<>(EVENT_QUEUE_SIZE);


	/**
	 * Message consumer that simply enqueues received messages received by this Server
	 */
	Consumer<Message> peerReceiveAction = new Consumer<Message>() {
		@Override
		public void accept(Message msg) {
			try {
				receiveQueue.put(msg);
			} catch (InterruptedException e) {
				log.warn("Interrupt on peer receive queue!");
			}
		}
	};

	/**
	 * Connection manager instance.
	 */
	protected ConnectionManager manager;

	/**
	 * Store to use for all threads associated with this server instance
	 */
	private final AStore store;

	private final HashMap<Keyword, Object> config;

	/**
	 * Flag for a running server. Setting to false will terminate server threads.
	 */
	private volatile boolean isRunning = false;

	private NIOServer nio;
	private Thread receiverThread = null;
	private Thread updateThread = null;

	/**
	 * The Peer instance current state for this server. Will be updated based on peer events.
	 */
	private Peer peer;

	/**
	 * The Peer Controller Address
	 */
	private Address controller;

	/**
	 * The list of new transactions to be added to the next Block. Accessed only in update loop
	 *
	 * Must all have been fully persisted.
	 */
	private ArrayList<SignedData<ATransaction>> newTransactions = new ArrayList<>();

	/**
	 * The set of queued partial messages pending missing data.
	 *
	 * Delivery will be re-attempted when missing data is provided
	 */
	private HashMap<Hash, Message> partialMessages = new HashMap<Hash, Message>();

	/**
	 * The list of new beliefs received from remote peers the block being created
	 * Should only modify with the lock for this Server held.
	 */
	private HashMap<AccountKey, SignedData<Belief>> newBeliefs = new HashMap<>();


	/**
	 * Hostname of the peer server.
	 */
	String hostname;

	private IServerEvent eventHook = null;

	private Server(HashMap<Keyword, Object> config) throws TimeoutException, IOException {
		AStore configStore = (AStore) config.get(Keywords.STORE);
		this.store = (configStore == null) ? Stores.current() : configStore;

		// assign the event hook if set
		if (config.containsKey(Keywords.EVENT_HOOK)) {
			Object maybeHook=config.get(Keywords.EVENT_HOOK);
			if (maybeHook instanceof IServerEvent) {
				this.eventHook = (IServerEvent)maybeHook;
			}
		}
		// Switch to use the configured store for setup, saving the caller store
		final AStore savedStore=Stores.current();
		try {
			Stores.setCurrent(store);
			this.config = config;
			// now setup the connection manager
			this.manager = new ConnectionManager(this);

			this.peer = establishPeer();

			establishController();

			nio = NIOServer.create(this, receiveQueue);

		} finally {
			Stores.setCurrent(savedStore);
		}
	}

	/**
	 * Establish the controller Account for this Peer.
	 */
	private void establishController() {
		Address controlAddress=RT.castAddress(getConfig().get(Keywords.CONTROLLER));
		if (controlAddress==null) {
			controlAddress=peer.getController();
			if (controlAddress==null) {
				throw new IllegalStateException("Peer Controller account does not exist for Peer Key: "+peer.getPeerKey());
			}
		}
		AccountStatus as=peer.getConsensusState().getAccount(controlAddress);
		if (as==null) {
			throw new IllegalStateException("Peer Controller Account does not exist: "+controlAddress);
		}
		if (!as.getAccountKey().equals(getKeyPair().getAccountKey())) {
			throw new IllegalStateException("Server keypair does not match keypair for control account: "+controlAddress);
		}
		this.setPeerController(controlAddress);
	}

	@SuppressWarnings("unchecked")
	private Peer establishPeer() throws TimeoutException, IOException {
		log.info("Establishing Peer with store: {}",Stores.current());
		try {
			AKeyPair keyPair = (AKeyPair) getConfig().get(Keywords.KEYPAIR);
			if (keyPair==null) throw new IllegalArgumentException("No Peer Key Pair provided in config");

			Object source=getConfig().get(Keywords.SOURCE);
			if (Utils.bool(source)) {
				// Peer sync case
				InetSocketAddress sourceAddr=Utils.toInetSocketAddress(source);
				Convex convex=Convex.connect(sourceAddr);
				log.info("Attempting Peer Sync with: "+sourceAddr);
				Future<Result> statusF=convex.requestStatus();
				long timeout=establishTimeout();
				AVector<ACell> status=statusF.get(timeout,TimeUnit.MILLISECONDS).getValue();
				if (status.count()!=Constants.STATUS_COUNT) {
					throw new Error("Bad status message from remote Peer");
				}
				Hash beliefHash=RT.ensureHash(status.get(0));
				Hash networkID=RT.ensureHash(status.get(2));
				State genF=(State) convex.acquire(networkID).get(timeout,TimeUnit.MILLISECONDS);
				log.info("Retreived Genesis State: "+networkID);
				SignedData<Belief> belF=(SignedData<Belief>) convex.acquire(beliefHash).get(timeout,TimeUnit.MILLISECONDS);
				log.info("Retreived Peer Signed Belief: "+networkID);

				Peer peer=Peer.create(keyPair, genF, belF.getValue());
				return peer;

			} else if (Utils.bool(getConfig().get(Keywords.RESTORE))) {
				// Restore from storage case
				try {

					Peer peer = Peer.restorePeer(store, keyPair);
					if (peer != null) {
						log.info("Restored Peer with root data hash: {}",store.getRootHash());
						return peer;
					}
				} catch (Throwable e) {
					log.error("Can't restore Peer from store: {}",e);
				}
			}
			State genesisState = (State) config.get(Keywords.STATE);
			if (genesisState!=null) {
				log.info("Defaulting to standard Peer startup with genesis state: "+genesisState.getHash());
			} else {
				AccountKey peerKey=keyPair.getAccountKey();
				genesisState=Init.createState(List.of(peerKey));
				log.info("Created new genesis state: "+genesisState.getHash()+ " with initial peer: "+peerKey);
			}
			return Peer.createGenesisPeer(keyPair,genesisState);
		} catch (ExecutionException|InterruptedException e) {
			throw Utils.sneakyThrow(e);
		}
	}

	private long establishTimeout() {
		Object maybeTimeout=getConfig().get(Keywords.TIMEOUT);
		if (maybeTimeout==null) return Constants.PEER_SYNC_TIMEOUT;
		Utils.toInt(maybeTimeout);
		return 0;
	}

	/**
	 * Creates a new unlaunched Server with a given config. Reference to config is kept: don't
	 * mutate elsewhere.
	 *
	 * @param config Server configuration map
	 *
	 * @param event Event interface where the server will send information about the peer
	 * @return New Server instance
	 * @throws IOException If an IO Error occurred establishing the Peer
	 * @throws TimeoutException If Peer creation timed out
	 */
	public static Server create(HashMap<Keyword, Object> config) throws TimeoutException, IOException {
		return new Server(config);
	}

	/**
	 * Gets the current Belief held by this PeerServer
	 *
	 * @return Current Belief
	 */
	public Belief getBelief() {
		return peer.getBelief();
	}

	/**
	 * Gets the current Peer data structure for this Server.
	 *
	 * @return Current Peer
	 */
	public Peer getPeer() {
		return peer;
	}

	/**
	 * Gets the desired host name for this Peer
	 * @return Hostname String
	 */
	public String getHostname() {
		return hostname;
	}

	/**
	 * Launch the Peer Server, including all main server threads
	 */
	public void launch() {
		Object p = getConfig().get(Keywords.PORT);
		Integer port = (p == null) ? null : Utils.toInt(p);

		try {
			nio.launch(port);
			port = nio.getPort(); // get the actual port (may be auto-allocated)

			if (getConfig().containsKey(Keywords.URL)) {
				hostname = (String) getConfig().get(Keywords.URL);
			} else {
				hostname = String.format("localhost:%d", port);
			}

			// set running status now, so that loops don't terminate
			isRunning = true;

			// Start connection manager loop
			manager.start();

			receiverThread = new Thread(receiverLoop, "Receive Loop on port: " + port);
			receiverThread.setDaemon(true);
			receiverThread.start();

			// Start Peer update thread
			updateThread = new Thread(updateLoop, "Update Loop on port: " + port);
			updateThread.setDaemon(true);
			updateThread.start();


			// Close server on shutdown, should be before Etch stores in priority
			Shutdown.addHook(Shutdown.SERVER, new Runnable() {
				@Override
				public void run() {
					close();
				}
			});

			log.info( "Peer Server started with Peer Address: {}",getPeerKey());
		} catch (Throwable e) {
			throw new Error("Failed to launch Server on port: " + port, e);
		}
	}

	public boolean joinNetwork(AKeyPair keyPair, Address address, String remoteHostname, SignedData<Belief> signedBelief) {
		if (remoteHostname == null) {
			return false;
		}
		InetSocketAddress remotePeerAddress = Utils.toInetSocketAddress(remoteHostname);
		int retryCount = 5;
		Convex convex = null;
		Result result = null;
		while (retryCount > 0) {
			try {
				convex = Convex.connect(remotePeerAddress, address, keyPair);
				Future<Result> cf =  convex.requestStatus();
				result = cf.get(2000, TimeUnit.MILLISECONDS);
				retryCount = 0;
			} catch (IOException | InterruptedException | ExecutionException | TimeoutException e ) {
				// raiseServerMessage("unable to connect to remote peer at " + remoteHostname + ". Retrying " + e);
				retryCount --;
			}
		}
		if ((convex==null)||(result == null)) {
			log.warn("Failed to join network: Cannot connect to remote peer at {}",remoteHostname);
			return false;
		}


		AVector<ACell> values = result.getValue();
		// Hash beliefHash = RT.ensureHash(values.get(0));
		// Hash stateHash = RT.ensureHash(values.get(1));

		// check the initStateHash to see if this is the network we want to join?
		Hash remoteNetworkID = RT.ensureHash(values.get(2));
		if (!Utils.equals(peer.getNetworkID(),remoteNetworkID)) {
			throw new Error("Failed to join network, we want Network ID "+peer.getNetworkID()+" but remote Peer reported "+remoteNetworkID);
		}

		try {
			if (signedBelief != null) {
				this.peer = this.peer.mergeBeliefs(signedBelief.getValue());
			}
		} catch (BadSignatureException | InvalidDataException e) {
			throw new Error("Cannot merge to latest belief " + e);
		}
		raiseServerChange("join network");

		return true;
	}

	/**
	 * Process a message received from a peer or client. We know at this point that the
	 * message parsed successfully, not much else.....
	 *
	 * If the message is partial, will be queued pending delivery of missing data.
	 *
	 * Runs on receiver thread
	 *
	 * @param m
	 */
	private void processMessage(Message m) {
		MessageType type = m.getType();
		log.trace("Processing message {}",type);
		try {
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
			case MISSING_DATA:
				processMissingData(m);
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
			}

		} catch (MissingDataException e) {
			Hash missingHash = e.getMissingHash();
			log.trace("Missing data: {} in message of type {}" , missingHash,type);
			try {
				registerPartialMessage(missingHash, m);
				m.getConnection().sendMissingData(missingHash);
				log.trace("Requested missing data {} for partial message",missingHash);
			} catch (IOException ex) {
				log.warn( "Exception while requesting missing data: {}" + ex);
			}
		} catch (BadFormatException | ClassCastException | NullPointerException e) {
			log.warn("Error processing client message: {}", e);
		}
	}

	/**
	 * Respond to a request for missing data, on a best-efforts basis. Requests for
	 * missing data we do not hold are ignored.
	 *
	 * @param m
	 * @throws BadFormatException
	 */
	private void processMissingData(Message m) throws BadFormatException {
		// payload for a missing data request should be a valid Hash
		Hash h = RT.ensureHash(m.getPayload());
		if (h == null) throw new BadFormatException("Hash required for missing data message");

		Ref<?> r = store.refForHash(h);
		if (r != null) {
			try {
				ACell data = r.getValue();
				boolean sent = m.getConnection().sendData(data);
				// log.trace( "Sent missing data for hash: {} with type {}",Utils.getClassName(data));
				if (!sent) {
					log.debug("Can't send missing data for hash {} due to full buffer",h);
				}
			} catch (IOException e) {
				log.warn("Unable to deliver missing data for {} due to exception: {}", h, e);
			}
		} else {
			log.debug("Unable to provide missing data for {} from store: {}", h,Stores.current());
		}
	}

	@SuppressWarnings("unchecked")
	private void processTransact(Message m) {
		// query is a vector [id , signed-object]
		AVector<ACell> v = m.getPayload();
		SignedData<ATransaction> sd = (SignedData<ATransaction>) v.get(1);

		// System.out.println("transact: "+v);

		// Persist the signed transaction. Might throw MissingDataException?
		// If we already have the transaction persisted, will get signature status
		ACell.createPersisted(sd);

		if (!sd.checkSignature()) {
			// terminate the connection, dishonest client?
			try {
				// TODO: throttle?
				m.getConnection().sendResult(m.getID(), Strings.create("Bad Signature!"), ErrorCodes.SIGNATURE);
			} catch (IOException e) {
				// Ignore?? Connection probably gone anyway
			}
			log.info("Bad signature from Client! {}" , sd);
			return;
		}

		registerInterest(sd.getHash(), m);
		try {
			eventQueue.put(sd);
		} catch (InterruptedException e) {
			log.warn("Unexpected interruption adding transaction to event queue!");
		}
	}

	/**
	 * Called by a remote peer to close connections to the remote peer.
	 *
	 */
	private void processClose(Message m) {
		SignedData<AccountKey> signedPeerKey = m.getPayload();
		AccountKey remotePeerKey = RT.ensureAccountKey(signedPeerKey.getValue());
		manager.closeConnection(remotePeerKey);
		raiseServerChange("connection");
	}

	/**
	 * Checks if received data fulfils the requirement for a partial message If so,
	 * process the message again.
	 *
	 * @param hash
	 * @return true if the data request resulted in a re-queued message, false
	 *         otherwise
	 */
	private boolean maybeProcessPartial(Hash hash) {
		Message m;
		synchronized (partialMessages) {
			m = partialMessages.get(hash);

			if (m != null) {
				log.trace( "Attempting to re-queue partial message due to received hash: ",hash);
				if (receiveQueue.offer(m)) {
					partialMessages.remove(hash);
					return true;
				} else {
					log.warn( "Queue full for message with received hash: {}", hash);
				}
			}
		}
		return false;
	}

	/**
	 * Stores a partial message for potential later handling.
	 *
	 * @param missingHash Hash of missing data dependency
	 * @param m           Message to re-attempt later when missing data is received.
	 */
	private void registerPartialMessage(Hash missingHash, Message m) {
		synchronized (partialMessages) {
			log.trace( "Registering partial message with missing hash: " ,missingHash);
			partialMessages.put(missingHash, m);
		}
	}

	/**
	 * Register of client interests in receiving transaction responses
	 */
	private HashMap<Hash, Message> interests = new HashMap<>();

	private void registerInterest(Hash signedTransactionHash, Message m) {
		interests.put(signedTransactionHash, m);
	}

	/**
	 * Handle general Belief update, taking belief registered in newBeliefs
	 *
	 * @return true if Peer Belief changed, false otherwise
	 * @throws InterruptedException
	 */
	protected boolean maybeUpdateBelief() throws InterruptedException {
		long oldConsensusPoint = peer.getConsensusPoint();

		// possibly have own transactions to publish
		maybePostOwnTransactions();

		// publish new blocks if needed. Guaranteed to change belief if this happens
		boolean published = maybePublishBlock();

		// only do belief merge if needed: either after publishing a new block or with
		// incoming beliefs
		if ((!published) && newBeliefs.isEmpty()) return false;

		// Update Peer timestamp first. This determines what we might accept.
		peer = peer.updateTimestamp(Utils.getCurrentTimestamp());

		boolean updated = maybeMergeBeliefs();
		// Must skip broadcast if we haven't published a new Block or updated our own Order
		if (!(updated||published)) return false;

		// At this point we know our Order should have changed
		final Belief belief = peer.getBelief();

		broadcastBelief(belief);

		// Report transaction results
		long newConsensusPoint = peer.getConsensusPoint();
		if (newConsensusPoint > oldConsensusPoint) {
			log.debug("Consensus point update from {} to {}" ,oldConsensusPoint , newConsensusPoint);
			for (long i = oldConsensusPoint; i < newConsensusPoint; i++) {
				Block block = peer.getPeerOrder().getBlock(i);
				BlockResult br = peer.getBlockResult(i);
				reportTransactions(block, br);
			}
		}

		return true;
	}

	/**
	 * Time of last belief broadcast
	 */
	private long lastBroadcastBelief=0;
	private long broadcastCount=0L;

	private void broadcastBelief(Belief belief) {
		// At this point we know something updated our belief, so we want to rebroadcast
		// belief to network
		Consumer<Ref<ACell>> noveltyHandler = r -> {
			ACell o = r.getValue();
			if (o == belief) return; // skip sending data for belief cell itself, will be BELIEF payload
			Message msg = Message.createData(o);
            // broadcast to all peers trusted or not
			manager.broadcast(msg, false);
		};

		// persist the state of the Peer, announcing the new Belief
		// (ensure we can handle missing data requests etc.)
		peer=peer.persistState(noveltyHandler);

		// Broadcast latest Belief to connected Peers
		SignedData<Belief> sb = peer.getSignedBelief();

		Message msg = Message.createBelief(sb);

        // at the moment broadcast to all peers trusted or not TODO: recheck this
		manager.broadcast(msg, false);
		lastBroadcastBelief=Utils.getCurrentTimestamp();
		broadcastCount++;
	}

	/**
	 * Gets the number of belief broadcasts made by this Peer
	 * @return Count of broadcasts from this Server instance
	 */
	public long getBroadcastCount() {
		return broadcastCount;
	}

	private long lastBlockPublishedTime=0L;

	/**
	 * Checks for pending transactions, and if found propose them as a new Block.
	 *
	 * @return True if a new block is published, false otherwise.
	 */
	protected boolean maybePublishBlock() {
		long timestamp=Utils.getCurrentTimestamp();
		// skip if recently published a block
		if ((lastBlockPublishedTime+Constants.MIN_BLOCK_TIME)>timestamp) return false;

		Block block=null;
		int n = newTransactions.size();
		if (n == 0) return false;
		// TODO: smaller block if too many transactions?
		block = Block.create(timestamp, (List<SignedData<ATransaction>>) newTransactions, peer.getPeerKey());
		newTransactions.clear();

		ACell.createPersisted(block);

		Peer newPeer = peer.proposeBlock(block);
		log.info("New block proposed: {} transaction(s), hash={}", block.getTransactions().count(), block.getHash());

		peer = newPeer;
		lastBlockPublishedTime=timestamp;
		return true;
	}

	private long lastOwnTransactionTimestamp=0L;

	private static final long OWN_TRANSACTIONS_DELAY=300;

	/**
	 * Gets the Peer controller Address
	 * @return Peer controller Address
	 */
	public Address getPeerController() {
		return controller;
	}

	/**
	 * Sets the Peer controller Address
	 * @param a Peer Controller Address to set
	 */
	public void setPeerController(Address a) {
		controller=a;
	}

	/**
	 * Adds an event to the inboud server event queue. May block.
	 * @throws InterruptedException
	 */
	public void queueEvent(SignedData<?> event) throws InterruptedException {
		eventQueue.put(event);
	}

	/**
	 * Check if the Peer want to send any of its own transactions
	 * @param transactionList List of transactions to add to.
	 */
	private void maybePostOwnTransactions() {
		if (!Utils.bool(config.get(Keywords.AUTO_MANAGE))) return;

		State s=getPeer().getConsensusState();
		long ts=Utils.getCurrentTimestamp();

		// If no connections yet, don't try this
		if (manager.getConnectionCount()==0) return;

		// If we already did this recently, don't try again
		if (ts<(lastOwnTransactionTimestamp+OWN_TRANSACTIONS_DELAY)) return;

		lastOwnTransactionTimestamp=ts; // mark this timestamp

		String desiredHostname=getHostname(); // Intended hostname
		AccountKey peerKey=getPeerKey();
		PeerStatus ps=s.getPeer(peerKey);
		AString chn=ps.getHostname();
		String currentHostname=(chn==null)?null:chn.toString();

		// Try to set hostname if not correctly set
		trySetHostname:
		if (!Utils.equals(desiredHostname, currentHostname)) {
			log.info("Trying to update own hostname from: {} to {}",currentHostname,desiredHostname);
			Address address=ps.getController();
			if (address==null) break trySetHostname;
			AccountStatus as=s.getAccount(address);
			if (as==null) break trySetHostname;
			if (!Utils.equals(getPeerKey(), as.getAccountKey())) break trySetHostname;

			String code;
			if (desiredHostname==null) {
				code = String.format("(set-peer-data %s {:url nil})", peerKey);
			} else {
				code = String.format("(set-peer-data %s {:url \"%s\"})", peerKey, desiredHostname);
			}
			ACell message = Reader.read(code);
			ATransaction transaction = Invoke.create(address, as.getSequence()+1, message);
			newTransactions.add(getKeyPair().signData(transaction));
		}
	}


	/**
	 * Checks for mergeable remote beliefs, and if found merge and update own
	 * belief.
	 *
	 * @return True if Peer Belief Order was changed, false otherwise.
	 */
	protected boolean maybeMergeBeliefs() {
		try {
			// First get the set of new beliefs for merging
			Belief[] beliefs;
			synchronized (newBeliefs) {
				int n = newBeliefs.size();
				beliefs = new Belief[n];
				int i = 0;
				for (AccountKey addr : newBeliefs.keySet()) {
					beliefs[i++] = newBeliefs.get(addr).getValue();
				}
				newBeliefs.clear();
			}
			Peer newPeer = peer.mergeBeliefs(beliefs);

			// Check for substantive change (i.e. Orders updated, can ignore timestamp)
			if (newPeer.getBelief().getOrders().equals(peer.getBelief().getOrders())) return false;

			log.debug( "New merged Belief update: {}" ,newPeer.getBelief().getHash());
			// we merged successfully, so clear pending beliefs and update Peer
			peer = newPeer;
			return true;
		} catch (MissingDataException e) {
			// Shouldn't happen if beliefs are persisted
			// e.printStackTrace();
			throw new Error("Missing data in belief update: " + e.getMissingHash().toHexString(), e);
		} catch (BadSignatureException e) {
			// Shouldn't happen if Beliefs are already validated
			// e.printStackTrace();
			throw new Error("Bad Signature in belief update!", e);
		} catch (InvalidDataException e) {
			// Shouldn't happen if Beliefs are already validated
			// e.printStackTrace();
			throw new Error("Invalid data in belief update!", e);
		}
	}

	private void processStatus(Message m) {
		try {
			// We can ignore payload

			Connection pc = m.getConnection();
			log.debug( "Processing status request from: {}" ,pc.getRemoteAddress());
			// log.log(LEVEL_MESSAGE, "Processing query: " + form + " with address: " +
			// address);

			Peer peer=this.getPeer();
			Hash beliefHash=peer.getSignedBelief().getHash();
			Hash stateHash=peer.getStates().getHash();
			Hash initialStateHash=peer.getStates().get(0).getHash();
			AccountKey peerKey=getPeerKey();
			Hash consensusHash=peer.getConsensusState().getHash();

			AVector<ACell> reply=Vectors.of(beliefHash,stateHash,initialStateHash,peerKey,consensusHash);

			pc.sendResult(m.getID(), reply);
		} catch (Throwable t) {
			log.warn("Status Request Error: {}", t);
		}
	}

	private void processChallenge(Message m) {
		manager.processChallenge(m, peer);
	}

	private void processResponse(Message m) {
		manager.processResponse(m, peer);
	}

	private void processQuery(Message m) {
		try {
			// query is a vector [id , form, address?]
			AVector<ACell> v = m.getPayload();
			CVMLong id = (CVMLong) v.get(0);
			ACell form = v.get(1);

			// extract the Address, or use HERO if not available.
			Address address = (Address) v.get(2);

			Connection pc = m.getConnection();
			log.debug( "Processing query: {} with address: {}" , form, address);
			// log.log(LEVEL_MESSAGE, "Processing query: " + form + " with address: " +
			// address);
			Context<ACell> resultContext = peer.executeQuery(form, address);
			boolean resultReturned;

			if (resultContext.isExceptional()) {
				AExceptional err = resultContext.getExceptional();
				ACell code = err.getCode();
				ACell message = err.getMessage();

				resultReturned = pc.sendResult(id, message, code);
			} else {
				resultReturned = pc.sendResult(id, resultContext.getResult());
			}

			if (!resultReturned) {
				log.warn("Failed to send query result back to client with ID: {}", id);
			}

		} catch (Throwable t) {
			log.warn("Query Error: {}", t);
		}
	}

	private void processData(Message m) {
		ACell payload = m.getPayload();

		// TODO: be smarter about this? hold a per-client queue for a while?
		Ref<?> r = Ref.get(payload);
		r = r.persistShallow();
		Hash payloadHash = r.getHash();

		if (log.isTraceEnabled()) {
			log.trace( "Processing DATA of type: " + Utils.getClassName(payload) + " with hash: "
					+ payloadHash.toHexString() + " and encoding: " + Format.encodedBlob(payload).toHexString());
		}
		// if our data satisfies a missing data object, need to process it
		maybeProcessPartial(r.getHash());
	}

	/**
	 * Process an incoming message that represents a Belief
	 *
	 * @param m
	 */
	private void processBelief(Message m) {
		Connection pc = m.getConnection();
		if (pc.isClosed()) return; // skip messages from closed peer

		ACell o = m.getPayload();

		Ref<ACell> ref = Ref.get(o);
		try {
			// check we can persist the new belief
			// May also pick up cached signature verification if already held
			ref = ref.persist();

			@SuppressWarnings("unchecked")
			SignedData<Belief> receivedBelief = (SignedData<Belief>) o;
			receivedBelief.validateSignature();

			// TODO: validate trusted connection?
			// TODO: can drop Beliefs if under pressure?

			eventQueue.put(receivedBelief);
		} catch (ClassCastException e) {
			// bad message?
			log.warn("Exception due to bad message from peer? {}" ,e);
		} catch (BadSignatureException e) {
			// we got sent a bad signature.
			// TODO: Probably need to slash peer? but ignore for now
			log.warn("Bad signed belief from peer: " + Utils.print(o));
		} catch (InterruptedException e) {
			throw Utils.sneakyThrow(e);
		}
	}

	/*
	 * Loop to process messages from the receive queue
	 */
	private Runnable receiverLoop = new Runnable() {
		@Override
		public void run() {
			Stores.setCurrent(getStore()); // ensure the loop uses this Server's store

			try {
				log.debug("Reciever thread started for peer at {}", getHostAddress());

				while (isRunning) { // loop until server terminated
					Message m = receiveQueue.poll(100, TimeUnit.MILLISECONDS);
					if (m != null) {
						processMessage(m);
					}
				}

				log.debug("Reciever thread terminated normally for peer {}", this);
			} catch (InterruptedException e) {
				log.debug("Receiver thread interrupted ");
			} catch (Throwable e) {
				log.warn("Receiver thread terminated abnormally! ");
				log.error("Server FAILED: " + e.getMessage());
				e.printStackTrace();
			}
		}
	};

	/*
	 * Runnable loop for managing Server state updates
	 */
	private final Runnable updateLoop = new Runnable() {
		@Override
		public void run() {
			Stores.setCurrent(getStore()); // ensure the loop uses this Server's store
			try {
				// loop while the server is running
				while (isRunning) {
					long timestamp=Utils.getCurrentTimestamp();

					// Try belief update
					if (maybeUpdateBelief() ) {
						raiseServerChange("consensus");
					}

					// Maybe rebroadcast Belief if not done recently
					if ((lastBroadcastBelief+Constants.REBROADCAST_DELAY)<timestamp) {
						// rebroadcast if there is still stuff outstanding for consensus
						if (peer.getConsensusPoint()<peer.getPeerOrder().getBlockCount()) {
							broadcastBelief(peer.getBelief());
						}
					}

					// Maybe sleep a bit, wait for some messages to accumulate
					awaitEvents();
				}
			} catch (InterruptedException e) {
				log.debug("Terminating Server update due to interrupt");
			} catch (Throwable e) {
				log.error("Unexpected exception in server update loop: {}", e);
				log.error("Terminating Server update");
				e.printStackTrace();
			}
		}
	};

	@SuppressWarnings("unchecked")
	private void awaitEvents() throws InterruptedException {
		SignedData<?> firstEvent=eventQueue.poll(SERVER_UPDATE_PAUSE, TimeUnit.MILLISECONDS);
		if (firstEvent==null) return;
		ArrayList<SignedData<?>> allEvents=new ArrayList<>();
		allEvents.add(firstEvent);
		eventQueue.drainTo(allEvents);
		for (SignedData<?> signedEvent: allEvents) {
			ACell event=signedEvent.getValue();
			if (event instanceof ATransaction) {
				SignedData<ATransaction> receivedTrans=(SignedData<ATransaction>)signedEvent;
				newTransactions.add(receivedTrans);
			} else if (event instanceof Belief) {
				SignedData<Belief> receivedBelief=(SignedData<Belief>)signedEvent;
				AccountKey addr = receivedBelief.getAccountKey();
				SignedData<Belief> current = newBeliefs.get(addr);
				// Make sure the Belief is the latest from a Peer
				if ((current == null) || (current.getValue().getTimestamp() <= receivedBelief.getValue()
						.getTimestamp())) {
					// Add to map of new Beliefs received for each Peer
					newBeliefs.put(addr, receivedBelief);

					// Notify the update thread that there is something new to handle
					log.debug("Valid belief received by peer at {}: {}"
							,getHostAddress(),receivedBelief.getValue().getHash());
				}
			} else {
				throw new Error("Unexpected type in event queue!"+Utils.getClassName(event));
			}
		}
	}

	private void reportTransactions(Block block, BlockResult br) {
		// TODO: consider culling old interests after some time period
		int nTrans = block.length();
		for (long j = 0; j < nTrans; j++) {
			try {
				SignedData<ATransaction> t = block.getTransactions().get(j);
				Hash h = t.getHash();
				Message m = interests.get(h);
				if (m != null) {
					log.trace("Returning transaction result to ", m.getConnection().getRemoteAddress());

					Connection pc = m.getConnection();
					if ((pc == null) || pc.isClosed()) continue;
					ACell id = m.getID();
					Result res = br.getResults().get(j).withID(id);

					pc.sendResult(res);
					interests.remove(h);
				}
			} catch (Throwable e) {
				log.warn("Exception while sending Result: ",e);
				// ignore
			}
		}
	}

	/**
	 * Gets the port that this Server is currently accepting connections on
	 * @return Port number
	 */
	public int getPort() {
		return nio.getPort();
	}

	@Override
	public void finalize() {
		close();
	}

	/**
	 * Writes the Peer data to the configured store.
	 *
	 * This will overwrite any previously persisted peer data.
	 */
	public void persistPeerData() {
		AStore tempStore = Stores.current();
		try {
			Stores.setCurrent(store);
			ACell peerData = peer.toData();
			Ref<?> peerRef = ACell.createPersisted(peerData);
			Hash peerHash = peerRef.getHash();
			store.setRootHash(peerHash);
			log.info( "Stored peer data for Server with hash: {}", peerHash.toHexString());
		} catch (Throwable e) {
			log.warn("Failed to persist peer state when closing server: {}" ,e);
		} finally {
			Stores.setCurrent(tempStore);
		}
	}

	@Override
	public void close() {
		// persist peer state if necessary
		if ((peer != null) && Utils.bool(getConfig().get(Keywords.PERSIST))) {
			persistPeerData();
		}

		// TODO: not much point signing this?
		SignedData<ACell> signedPeerKey = peer.sign(peer.getPeerKey());
		Message msg = Message.createGoodBye(signedPeerKey);

		// broadcast GOODBYE message to all outgoing remote peers
		manager.broadcast(msg, false);

		isRunning = false;
		if (updateThread != null) {
			updateThread.interrupt();
			try {
				updateThread.join(100);
			} catch (InterruptedException e) {
				// Ignore
			}
		}
		if (receiverThread != null) {
			receiverThread.interrupt();
			try {
				receiverThread.join(100);
			} catch (InterruptedException e) {
				// Ignore
			}
		}
		manager.close();
		nio.close();
		// Note we don't do store.close(); because we don't own the store.
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
	 * same store instance for all Server threads.
	 *
	 * @return Store instance
	 */
	public AStore getStore() {
		return store;
	}

	/**
	 * Reports a server change event to the registered hook, if any
	 * @param reason Message for server change
	 */
	public void raiseServerChange(String reason) {
		if (eventHook != null) {
			ServerEvent serverEvent = ServerEvent.create(this, reason);
			eventHook.onServerChange(serverEvent);
		}
	}

	public ConnectionManager getConnectionManager() {
		return manager;
	}

	public HashMap<Keyword, Object> getConfig() {
		return config;
	}

	public Consumer<Message> getReceiveAction() {
		return peerReceiveAction;
	}

	/**
	 * Sets the desired host name for this Server
	 * @param string Desired host name String, e.g. "my-domain.com:12345"
	 */
	public void setHostname(String string) {
		hostname=string;
	}
}
