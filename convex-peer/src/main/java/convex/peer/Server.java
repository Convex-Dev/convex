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
import convex.core.Order;
import convex.core.Peer;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Maps;
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
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Shutdown;
import convex.core.util.Utils;
import convex.net.MessageType;
import convex.net.NIOServer;
import convex.net.message.Message;


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

	/**
	 * Default size for incoming client transaction queue
	 * Note: this limits TPS for client transactions, will send failures if overloaded
	 */
	private static final int TRANSACTION_QUEUE_SIZE = 500;
	
	/**
	 * Size of incoming Belief queue
	 */
	private static final int BELIEF_QUEUE_SIZE = 100;

	/**
	 * Maximum Pause for each iteration of Server Belief Merge loop.
	 * We handle Belief merges as fast as possible, pausing to poll for this period if none arrive
	 */
	private static final long BELIEF_MERGE_PAUSE = 5L;

	static final Logger log = LoggerFactory.getLogger(Server.class.getName());

	// private static final Level LEVEL_MESSAGE = Level.FINER;

	/**
	 * Queue for received messages to be processed by this Peer Server
	 */
	private BlockingQueue<Message> receiveQueue = new ArrayBlockingQueue<Message>(RECEIVE_QUEUE_SIZE);

	/**
	 * Queue for received Transactions submitted for clients of this Peer
	 */
	private BlockingQueue<SignedData<ATransaction>> transactionQueue;
	
	/**
	 * Queue for received events (Beliefs, Transactions) to be processed
	 */
	private BlockingQueue<SignedData<Belief>> beliefQueue;

	/**
	 * Message Consumer that simply enqueues received messages received by this peer
	 * Called on NIO thread: should never block for long
	 */
	Consumer<Message> peerReceiveAction = new Consumer<Message>() {
		@Override
		public void accept(Message msg) {
			try {
				queueMessage(msg);
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
	 * Connection manager instance.
	 */
	protected BeliefPropagator propagator;

	/**
	 * Store to use for all threads associated with this server instance
	 */
	private final AStore store;

	/**
	 * Configuration
	 */

	private final HashMap<Keyword, Object> config;

	private final ACell rootKey;

	/**
	 * Flag for a running server. Setting to false will terminate server threads.
	 */
	private volatile boolean isRunning = false;

	private NIOServer nio;
	private Thread receiverThread = null;
	private Thread beliefMergeThread = null;

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

		this.rootKey = (ACell)config.get(Keywords.ROOT_KEY);

		AStore configStore = (AStore) config.get(Keywords.STORE);
		this.store = (configStore == null) ? Stores.current() : configStore;

		// assign the event hook if set
		if (config.containsKey(Keywords.EVENT_HOOK)) {
			Object maybeHook=config.get(Keywords.EVENT_HOOK);
			if (maybeHook instanceof IServerEvent) {
				this.eventHook = (IServerEvent)maybeHook;
			}
		}
		
		// Set up Queue. TODO: use config if provided
		transactionQueue = new ArrayBlockingQueue<>(TRANSACTION_QUEUE_SIZE);
		beliefQueue = new ArrayBlockingQueue<>(BELIEF_QUEUE_SIZE);
		
		// Switch to use the configured store for setup, saving the caller store
		final AStore savedStore=Stores.current();
		try {
			Stores.setCurrent(store);
			this.config = config;
			// now setup the connection manager
			this.manager = new ConnectionManager(this);
			this.propagator = new BeliefPropagator(this);

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
		Address controlAddress=RT.toAddress(getConfig().get(Keywords.CONTROLLER));
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
			if (keyPair==null) {
				log.warn("No keypair provided for Server, deafulting to generated keypair for testing purposes");
				keyPair=AKeyPair.generate();
				log.warn("Generated keypair with public key: "+keyPair.getAccountKey());
			}

			Object source=getConfig().get(Keywords.SOURCE);
			if (Utils.bool(source)) {
				// Peer sync case
				InetSocketAddress sourceAddr=Utils.toInetSocketAddress(source);
				Convex convex=Convex.connect(sourceAddr);
				log.info("Attempting Peer Sync with: "+sourceAddr);
				long timeout = establishTimeout();
				
				// Sync status and genesis state
				Result result = convex.requestStatusSync(timeout);
				AVector<ACell> status = result.getValue();
				if (status == null || status.count()!=Constants.STATUS_COUNT) {
					throw new Error("Bad status message from remote Peer");
				}
				Hash beliefHash=RT.ensureHash(status.get(0));
				Hash networkID=RT.ensureHash(status.get(2));
				log.info("Attempting to sync genesis state with network: "+networkID);
				State genF=(State) convex.acquire(networkID).get(timeout,TimeUnit.MILLISECONDS);
				log.info("Retrieved Genesis State: "+networkID);
				
				// Belief acquisition
				log.info("Attempting to obtain peer Belief: "+beliefHash);
				SignedData<Belief> belF=null;
				long timeElapsed=0;
				while (belF==null) {
					try {
						belF=(SignedData<Belief>) convex.acquire(beliefHash).get(timeout,TimeUnit.MILLISECONDS);
					} catch (TimeoutException te) {
						timeElapsed+=timeout;
						log.info("Still waiting for Belief sync after "+timeElapsed/1000+"s");
					}
				}
				log.info("Retrieved Peer Signed Belief: "+beliefHash+ " with memory size: "+belF.getMemorySize());

				Peer peer=Peer.create(keyPair, genF, belF.getValue());
				return peer;

			} else if (Utils.bool(getConfig().get(Keywords.RESTORE))) {
				// Restore from storage case
				try {

					Peer peer = Peer.restorePeer(store, keyPair, rootKey);
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
	 * Creates a new (unlaunched) Server with a given config.
	 *
	 * @param config Server configuration map. Will be defensively copied.
	 *
	 * @return New Server instance
	 * @throws IOException If an IO Error occurred establishing the Peer
	 * @throws TimeoutException If Peer creation timed out
	 */
	public static Server create(HashMap<Keyword, Object> config) throws TimeoutException, IOException {
		return new Server(new HashMap<>(config));
	}

	/**
	 * Gets the current Belief held by this {@link Server}
	 *
	 * @return Current Belief
	 */
	public Belief getBelief() {
		return peer.getBelief();
	}

	/**
	 * Gets the current Peer data structure for this {@link Server}.
	 *
	 * @return Current Peer data
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
		AStore savedStore=Stores.current();
		try {
			Stores.setCurrent(store);

			HashMap<Keyword, Object> config = getConfig();

			Object p = config.get(Keywords.PORT);
			Integer port = (p == null) ? null : Utils.toInt(p);

			nio.launch((String)config.get(Keywords.BIND_ADDRESS), port);
			port = nio.getPort(); // Get the actual port (may be auto-allocated)

			if (getConfig().containsKey(Keywords.URL)) {
				hostname = (String) config.get(Keywords.URL);
				log.debug("Setting desired peer URL to: " + hostname);
			} else {
				hostname = null;
			}



			// set running status now, so that loops don't terminate
			isRunning = true;

			// Start connection manager loop
			manager.start();

			receiverThread = new Thread(receiverLoop, "Receive Loop on port: " + port);
			receiverThread.setDaemon(true);
			receiverThread.start();

			// Start Peer update thread
			beliefMergeThread = new Thread(beliefMergeLoop, "Belief Merge Loop on port: " + port);
			beliefMergeThread.setDaemon(true);
			beliefMergeThread.start();


			// Close server on shutdown, should be before Etch stores in priority
			Shutdown.addHook(Shutdown.SERVER, new Runnable() {
				@Override
				public void run() {
					close();
				}
			});

			// Connect to source peer if specified
			if (getConfig().containsKey(Keywords.SOURCE)) {
				Object s=getConfig().get(Keywords.SOURCE);
				InetSocketAddress sa=Utils.toInetSocketAddress(s);
				if (sa!=null) {
					if (manager.connectToPeer(sa)!=null) {
						log.debug("Automatically connected to :source peer at: {}",sa);
					} else {
						log.warn("Failed to connect to :source peer at: {}",sa);
					}
				} else {
					log.warn("Failed to parse :source peer address {}",s);
				}
			}

			log.info( "Peer Server started with Peer Address: {}",getPeerKey());
		} catch (Throwable e) {
			close();
			throw new Error("Failed to launch Server", e);
		} finally {
			Stores.setCurrent(savedStore);
		}
	}

	/**
	 * Process a message received from a peer or client. We know at this point that the
	 * message decoded successfully, not much else.....
	 * 
	 * SECURITY: Should anticipate malicious messages
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
			default:
				Result r=Result.create(m.getID(), Strings.create("Bad Message Type: "+type), ErrorCodes.ARGUMENT);
				m.reportResult(r);
				break;
			}

		} catch (MissingDataException e) {
			Hash missingHash = e.getMissingHash();
			log.trace("Missing data: {} in message of type {}" , missingHash,type);
			m.getConnection().registerPartialMessage(missingHash,m);
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
				boolean sent = m.sendData(data);
				// log.trace( "Sent missing data for hash: {} with type {}",Utils.getClassName(data));
				if (!sent) {
					log.debug("Can't send missing data for hash {} due to full buffer",h);
				}
			} catch (Exception e) {
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
				Result r=Result.create(m.getID(), Strings.BAD_SIGNATURE, ErrorCodes.SIGNATURE);
				m.reportResult(r);
			} catch (Exception e) {
				// Ignore?? Connection probably gone anyway
			}
			log.debug("Bad signature from Client! {}" , sd);
			return;
		}
		
		if (!(sd.getValue() instanceof ATransaction)) {
			Result r=Result.create(m.getID(), Strings.BAD_FORMAT, ErrorCodes.FORMAT);
			m.reportResult(r);
			return;
		}

		registerInterest(sd.getHash(), m);
		try {
			transactionQueue.put(sd);
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
	 * Register of client interests in receiving transaction responses
	 */
	private HashMap<Hash, Message> interests = new HashMap<>();

	/**
	 * Register interest in receiving a result for a transaction
	 * @param signedTransactionHash
	 * @param m
	 */
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

		// possibly have client transactions to publish
		maybePostClientTransactions();

		// possibly have own transactions to publish
		maybePostOwnTransactions();

		// publish new blocks if needed. Guaranteed to change belief if this happens
		boolean published = maybePublishBlock();

		// only do belief merge if needed: either after publishing a new block or with
		// incoming beliefs
		if ((!published) && newBeliefs.isEmpty()) return false;

		// Update Peer timestamp. This determines what we might accept.
		peer = peer.updateTimestamp(Utils.getCurrentTimestamp());

		boolean updated = maybeMergeBeliefs();
		// Must skip broadcast if we haven't published a new Block or updated our own Order
		if (!(updated||published)) return false;

		// At this point we know our Order should have changed
		propagator.broadcastBelief(peer);

		// Report transaction results
		long newConsensusPoint = peer.getConsensusPoint();
		if (newConsensusPoint > oldConsensusPoint) {
			log.debug("Consensus point update from {} to {}" ,oldConsensusPoint , newConsensusPoint);
			for (long i = oldConsensusPoint; i < newConsensusPoint; i++) {
				SignedData<Block> block = peer.getPeerOrder().getBlock(i);
				BlockResult br = peer.getBlockResult(i);
				reportTransactions(block.getValue(), br);
			}
		}

		return true;
	}


	private long beliefReceivedCount=0L;



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
		return beliefReceivedCount;
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
		if ((lastBlockPublishedTime+Constants.MIN_BLOCK_TIME)>=timestamp) return false;

		Block block=null;
		int n = newTransactions.size();
		if (n == 0) return false;
		// TODO: smaller block if too many transactions?
		block = Block.create(timestamp, (List<SignedData<ATransaction>>) newTransactions);
		newTransactions.clear();

		ACell.createPersisted(block);

		Peer newPeer = peer.proposeBlock(block);
		log.debug("New block proposed: {} transaction(s), hash={}", block.getTransactions().count(), block.getHash());

		peer = newPeer;
		lastBlockPublishedTime=timestamp;
		return true;
	}

	private long lastOwnTransactionTimestamp=0L;
	private long lastClientTransactionTimestamp=0L;

	/**
	 * Default minimum delay between proposing own transactions as a peer
	 */
	private static final long OWN_BLOCK_DELAY=1000;
	
	/**
	 * Default minimum delay between proposing a block of transactions
	 * Note: this limits the TPS for a single peer in terms of client transactions
	 * Also affects latency for client confirmations by up to this amount
	 */
	private static final long CLIENT_BLOCK_DELAY=100;

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
	 * Adds an event to the inbound server event queue. May block.
	 * @param event Signed event to add to inbound event queue
	 * @throws InterruptedException If interrupted while waiting
	 */
	public void queueBelief(SignedData<Belief> event) throws InterruptedException {
		beliefQueue.put(event);
	}
	
	/**
	 * Queues a message for processing by this Server. May block briefly.
	 * @param m Message to queue
	 * @throws InterruptedException If thread is interrupted
	 */
	public void queueMessage(Message m) throws InterruptedException {
		receiveQueue.put(m);
	}
	
	/**
	 * Check if the Peer want to send any of its own transactions
	 * @param transactionList List of transactions to add to.
	 */
	private void maybePostClientTransactions() {
		long ts=Utils.getCurrentTimestamp();
		// If we already did this recently, don't try again
		if (ts<(lastClientTransactionTimestamp+CLIENT_BLOCK_DELAY)) return;
		
		transactionQueue.drainTo(newTransactions);

		lastClientTransactionTimestamp=ts; // mark this timestamp
	}

	/**
	 * Check if the Peer want to send any of its own transactions
	 * @param transactionList List of transactions to add to.
	 */
	private void maybePostOwnTransactions() {
		State s=getPeer().getConsensusState();
		long ts=Utils.getCurrentTimestamp();

		// If we already did this recently, don't try again
		if (ts<(lastOwnTransactionTimestamp+OWN_BLOCK_DELAY)) return;
		lastOwnTransactionTimestamp=ts; // mark this timestamp

		// NOTE: beyond this point we only execute stuff when AUTO_MANAGE is set
		if (!Utils.bool(config.get(Keywords.AUTO_MANAGE))) return;

		String desiredHostname=getHostname(); // Intended hostname
		AccountKey peerKey=getPeerKey();
		PeerStatus ps=s.getPeer(peerKey);
		AString chn=ps.getHostname();
		String currentHostname=(chn==null)?null:chn.toString();
		
		// Try to set hostname if not correctly set
		trySetHostname:
		if (!Utils.equals(desiredHostname, currentHostname)) {
			log.debug("Trying to update own hostname from: {} to {}",currentHostname,desiredHostname);
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

			log.trace( "Processing status request from: {}" ,m.getOriginString());
			// log.log(LEVEL_MESSAGE, "Processing query: " + form + " with address: " +
			// address);

			AVector<ACell> reply = getStatusVector();

			m.reportResult(m.getID(), reply);
		} catch (Throwable t) {
			log.warn("Status Request Error: {}", t);
		}
	}

	/**
	 * Gets the status vector for the Peer
	 * 0 = latest signed belief hash
	 * 1 = states vector hash
	 * 2 = genesis state hash
	 * 3 = peer key
	 * 4 = consensus state
	 * 5 = consensus point
	 * 6 = proposal point
	 * 7 = ordering length
	 * @return Status vector
	 */
	public AVector<ACell> getStatusVector() {
		Peer peer=this.getPeer();
		SignedData<Belief> signedBelief = peer.getSignedBelief();
		
		Hash beliefHash=signedBelief.getHash();
		Hash statesHash=peer.getStates().getHash();
		Hash genesisHash=peer.getStates().get(0).getHash();
		AccountKey peerKey=getPeerKey();
		Hash consensusHash=peer.getConsensusState().getHash();
		
		Order order=peer.getPeerOrder();
		CVMLong cp = CVMLong.create(order.getConsensusPoint()) ;
		CVMLong pp = CVMLong.create(order.getProposalPoint()) ;
		CVMLong op = CVMLong.create(order.getBlockCount()) ;

		AVector<ACell> reply=Vectors.of(beliefHash,statesHash,genesisHash,peerKey,consensusHash, cp,pp,op);
		return reply;
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

			// extract the Address, might be null
			Address address = RT.ensureAddress(v.get(2));

			log.debug( "Processing query: {} with address: {}" , form, address);
			// log.log(LEVEL_MESSAGE, "Processing query: " + form + " with address: " +
			// address);
			Context<ACell> resultContext = peer.executeQuery(form, address);
			
			// Report result back to message sender
			boolean resultReturned= m.reportResult(Result.fromContext(id, resultContext));

			if (!resultReturned) {
				log.warn("Failed to send query result back to client with ID: {}", id);
			}

		} catch (Throwable t) {
			log.warn("Query Error: {}", t);
		}
	}

	private void processData(Message m) {
		ACell payload = m.getPayload();

		// Note: partial messages are handled in Connection now
		Ref<?> r = Ref.get(payload);
		r = r.persistShallow();
		Hash payloadHash = r.getHash();

		if (log.isTraceEnabled()) {
			log.trace( "Processing DATA of type: " + Utils.getClassName(payload) + " with hash: "
					+ payloadHash.toHexString() + " and encoding: " + Format.encodedBlob(payload).toHexString());
		}
	}

	/**
	 * Process an incoming message that represents a Belief
	 *
	 * @param m
	 */
	private void processBelief(Message m) {
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
			
			ACell b=receivedBelief.getValue(); // might not actually be a Belief
			if (!(b instanceof Belief)) {
				Result r=Result.create(m.getID(), Strings.BAD_FORMAT, ErrorCodes.FORMAT);
				m.reportResult(r);
				return;
			}

			boolean queued = beliefQueue.offer(receivedBelief);
			if (!queued) {
				log.debug("Incoming belief queue full");
			}
		} catch (ClassCastException e) {
			// bad message?
			log.warn("Exception due to bad message from peer? {}" ,e);
		} catch (BadSignatureException e) {
			// we got sent a bad signature.
			// TODO: Probably need to slash peer? but ignore for now
			log.warn("Bad signed belief from peer: " + Utils.print(o));
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
				log.debug("Receiver thread started for peer at {}", getHostAddress());

				while (isRunning) { // loop until server terminated
					Message m = receiveQueue.poll(100, TimeUnit.MILLISECONDS);
					if (m != null) {
						processMessage(m);
					}
				}

				log.debug("Receiver thread terminated normally for peer {}", this);
			} catch (InterruptedException e) {
				log.info("Receiver thread interrupted for peer {}", this);
			} catch (Throwable e) {
				log.error("Peer Server FAILED: Receiver thread terminated abnormally" + e.getMessage());
				e.printStackTrace();
			}
		}
	};

	/*
	 * Runnable loop for managing Server belief merges
	 */
	private final Runnable beliefMergeLoop = new Runnable() {
		@Override
		public void run() {
			Stores.setCurrent(getStore()); // ensure the loop uses this Server's store
			try {
				// loop while the server is running
				while (isRunning) {
					// Try belief update
					boolean beliefUpdated=maybeUpdateBelief();
					if (beliefUpdated) {
						raiseServerChange("consensus");
					}

					long timestamp=Utils.getCurrentTimestamp();

					// Broadcast Belief if changed or otherwise not done recently
					if (beliefUpdated||((propagator.lastBroadcastBelief+Constants.MAX_REBROADCAST_DELAY)<timestamp)) {
						// rebroadcast only if there is still stuff outstanding for consensus
						if (peer.getConsensusPoint()<peer.getPeerOrder().getBlockCount()) {
							propagator.broadcastBelief(peer);
						}
					}

					// Maybe sleep a bit, wait for some new events to accumulate
					awaitBeliefs();
				}
			} catch (InterruptedException e) {
				log.info("Terminating Belief Merge loop due to interrupt");
			} catch (Throwable e) {
				log.error("Unexpected exception in Belief Merge loop: {}", e);
				log.error("Terminating Server update");
				e.printStackTrace();
			}
		}
	};

	private void awaitBeliefs() throws InterruptedException {
		
		SignedData<Belief> firstEvent=beliefQueue.poll(BELIEF_MERGE_PAUSE, TimeUnit.MILLISECONDS);
		if (firstEvent==null) return;
		ArrayList<SignedData<Belief>> allBeliefs=new ArrayList<>();
		allBeliefs.add(firstEvent);
		beliefQueue.drainTo(allBeliefs);
		for (SignedData<Belief> signedEvent: allBeliefs) {
			SignedData<Belief> receivedBelief=(SignedData<Belief>)signedEvent;
			AccountKey addr = receivedBelief.getAccountKey();
			SignedData<Belief> current = newBeliefs.get(addr);
			
			// Make sure the Belief is the latest from a Peer
			if ((current == null) || (current.getValue().getTimestamp() <= receivedBelief.getValue()
					.getTimestamp())) {
				// Add to map of new Beliefs received for each Peer
				newBeliefs.put(addr, receivedBelief);
				beliefReceivedCount++;

				// Notify the update thread that there is something new to handle
				log.debug("Valid belief received by peer at {}: {}"
							,getHostAddress(),receivedBelief.getValue().getHash());
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
					ACell id = m.getID();
					log.trace("Returning tranaction result ID {} to {}", id,m.getOriginString());
					Result res = br.getResults().get(j);

					m.reportResult(res);
					
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
	 * Note: Does not flush buffers to disk. 
	 *
	 * This will overwrite any previously persisted peer data.
	 * @return True if successfully persisted, false in case of any error
	 */
	public boolean persistPeerData() {
		AStore tempStore = Stores.current();
		try {
			Stores.setCurrent(store);
			ACell rootData = peer.toData();

			if (rootKey != null) {
				Ref<AMap<ACell,ACell>> rootRef = store.refForHash(store.getRootHash());
				AMap<ACell,ACell> currentRootData = (rootRef == null)? Maps.empty() : rootRef.getValue();
				rootData = currentRootData.assoc(rootKey, rootData);
			}

			store.setRootData(rootData);
			log.info( "Stored peer data for Server with hash: {}", rootData.getHash().toHexString());
			return true;
		} catch (Throwable e) {
			log.warn("Failed to persist peer state: {}" ,e.getMessage());
			return false;
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
		if (beliefMergeThread != null) {
			beliefMergeThread.interrupt();
			try {
				beliefMergeThread.join(100);
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
	 * same store instance for all Server threads, as values may be shared.
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

	/**
	 * Gets the action to perform for an incoming client message
	 * @return Message consumer
	 */
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

	public boolean isLive() {
		return isRunning;
	}
}
