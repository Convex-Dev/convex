package convex.peer;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import convex.api.Convex;
import convex.api.Shutdown;
import convex.core.Belief;
import convex.core.Block;
import convex.core.BlockResult;
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
import convex.core.lang.Context;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.lang.impl.AExceptional;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
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

	// Maximum Pause for each iteration of Server update loop.
	private static final long SERVER_UPDATE_PAUSE = 1L;

	static final Logger log = Logger.getLogger(Server.class.getName());
	private static final Level LEVEL_BELIEF = Level.FINEST;
	static final Level LEVEL_SERVER = Level.FINER;
	private static final Level LEVEL_DATA = Level.FINEST;
	private static final Level LEVEL_PARTIAL = Level.FINER;

	private static final Level LEVEL_INFO = Level.FINER;
	// private static final Level LEVEL_MESSAGE = Level.FINER;

	/**
	 * Queue for received messages to be processed by this Peer Server
	 */
	private BlockingQueue<Message> receiveQueue = new ArrayBlockingQueue<Message>(RECEIVE_QUEUE_SIZE);

	/**
	 * Message consumer that simply enqueues received messages. Used for outward
	 * connections. i.e. ones this Server has made.
	 */
	Consumer<Message> peerReceiveAction = new Consumer<Message>() {
		@Override
		public void accept(Message msg) {
			receiveQueue.add(msg);
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

	private boolean isRunning = false;

	/**
	 * Flag to indicate if there are any new things for the server to process (Beliefs, transactions)
	 * can safely sleep a bit if nothing to do
	 */
	private boolean hasNewMessages = false;

	private NIOServer nio;
	private Thread receiverThread = null;
	private Thread updateThread = null;

	/**
	 * The Peer instance current state for this server. Will be updated based on peer events.
	 */
	private Peer peer;

	/**
	 * The list of transactions for the block being created Should only modify with
	 * the lock for this Server held.
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
	 * Should only modify with the lock for this Server help.
	 */
	private HashMap<AccountKey, SignedData<Belief>> newBeliefs = new HashMap<>();


	/**
	 * Hostname of the peer server.
	 */
	String hostname;

	private IServerEvent event = null;

	private Server(HashMap<Keyword, Object> config, IServerEvent event) {
		this.event = event;
		AStore configStore = (AStore) config.get(Keywords.STORE);
		this.store = (configStore == null) ? Stores.current() : configStore;

		AKeyPair keyPair = (AKeyPair) config.get(Keywords.KEYPAIR);
		if (keyPair==null) throw new IllegalArgumentException("No Peer Key Pair provided in config");

		// Switch to use the configured store for setup, saving the caller store
		final AStore savedStore=Stores.current();
		try {
			Stores.setCurrent(store);
			this.config = config;
			// now setup the connection manager
			this.manager = new ConnectionManager(this);

			this.peer = establishPeer(keyPair, config);

			nio = NIOServer.create(this, receiveQueue);

		} finally {
			Stores.setCurrent(savedStore);
		}
	}

	private Peer establishPeer(AKeyPair keyPair, Map<Keyword, Object> config2) {
		log.log(LEVEL_INFO, "Establishing Peer with store: "+Stores.current());

		if (Utils.bool(getConfig().get(Keywords.RESTORE))) {
			try {
				Hash hash = store.getRootHash();
				Peer peer = Peer.restorePeer(store, hash, keyPair);
				if (peer != null) {
					log.log(LEVEL_INFO, "Restored Peer with root data hash: "+hash);
					return peer;
				}
			} catch (Throwable e) {
				log.warning("Can't restore Peer from store: " + e.getMessage());
			}
		}
		log.log(LEVEL_INFO, "Defaulting to standard Peer startup.");
		return Peer.createStartupPeer(getConfig());
	}

	/**
	 * Creates a new unlaunched Server with a given config. Reference to config is kept: don't
	 * mutate elsewhere.
	 *
	 * @param config
	 * @return
	 */
	public static Server create(HashMap<Keyword, Object> config) {
		return create(config, null);
	}

	/**
	 * Creates a Server with a given config. Reference to config is kept: don't
	 * mutate elsewhere.
	 *
	 * @param config
	 *
	 * @param event Event interface where the server will send information about the peer
	 * @return
	 */
	public static Server create(HashMap<Keyword, Object> config, IServerEvent event) {
		return new Server(config, event);
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
	 * Gets the host name for this Peer
	 * @return Hostname String
	 */
	public String getHostname() {
		return hostname;
	}

	/**
	 * Launch the Peer Server, including all main server threads
	 */
	public synchronized void launch() {
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

			receiverThread = new Thread(receiverLoop, "Receive queue worker loop serving port: " + port);
			receiverThread.setDaemon(true);
			receiverThread.start();

			// Start Peer update thread
			updateThread = new Thread(updateLoop, "Server Belief update loop for port: " + port);
			updateThread.setDaemon(true);
			updateThread.start();


			// Close server on shutdown, should be before Etch stores in priority
			Shutdown.addHook(Shutdown.SERVER, new Runnable() {
				@Override
				public void run() {
					close();
				}
			});

			log.log(LEVEL_SERVER, "Peer Server started with Peer Address: " + getPeerKey().toChecksumHex());
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
			log.log(LEVEL_SERVER, "Failed to join network: Cannot connect to remote peer at "+remoteHostname);
			return false;
		}


		AVector<ACell> values = result.getValue();
		// Hash beliefHash = RT.ensureHash(values.get(0));
		// Hash stateHash = RT.ensureHash(values.get(1));

		// check the initStateHash to see if this is the network we want to join?
		Hash remoteNetworkID = RT.ensureHash(values.get(2));
		if (!Utils.equals(peer.getNetworkID(),remoteNetworkID)) {
			throw new Error("Failed to join network, we want Network ID "+peer.getNetworkID()+" but remote Peer repoerted "+remoteNetworkID);
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
	 * If the message is partial, will be queued pending delivery of missing data
	 *
	 * @param m
	 */
	private void processMessage(Message m) {
		MessageType type = m.getType();
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
			log.log(LEVEL_PARTIAL, "Missing data: " + missingHash.toHexString() + " in message of type " + type);
			try {
				registerPartialMessage(missingHash, m);
				m.getPeerConnection().sendMissingData(missingHash);
				log.log(LEVEL_PARTIAL,
						() -> "Requested missing data " + missingHash.toHexString() + " for partial message");
			} catch (IOException ex) {
				log.log(Level.WARNING, () -> "Exception while requesting missing data: " + ex.getMessage());
			}
		} catch (BadFormatException | ClassCastException | NullPointerException e) {
			log.warning("Error processing client message: " + e);
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
				m.getPeerConnection().sendData(data);
				log.log(LEVEL_INFO, "Sent missing data for hash: " + h.toHexString() + " with type "
						+ Utils.getClassName(data));
			} catch (IOException e) {
				log.log(LEVEL_INFO, "Unable to deliver missing data for " + h.toHexString() + " due to: " + e.getMessage());
			}
		} else {
			log.warning(
					() -> "Unable to provide missing data for " + h.toHexString() + " from store: " + Stores.current());
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
				m.getPeerConnection().sendResult(m.getID(), Strings.create("Bad Signature!"), ErrorCodes.SIGNATURE);
			} catch (IOException e) {
				// Ignore??
			}
			log.warning("Bad signature from Client! " + sd);
			return;
		}

		synchronized (newTransactions) {
			hasNewMessages=true;
			newTransactions.add(sd);
			registerInterest(sd.getHash(), m);
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
				log.log(LEVEL_PARTIAL,
						() -> "Attempting to re-queue partial message due to received hash: " + hash.toHexString());
				if (receiveQueue.offer(m)) {
					partialMessages.remove(hash);
					return true;
				} else {
					log.log(Level.WARNING, () -> "Queue full for message with received hash: " + hash.toHexString());
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
			log.log(LEVEL_PARTIAL, () -> "Registering partial message with missing hash: " + missingHash);
			partialMessages.put(missingHash, m);
		}
	}

	/**
	 * Register of client interests in receiving message responses
	 */
	private HashMap<Hash, Message> interests = new HashMap<>();

	private void registerInterest(Hash hash, Message m) {
		interests.put(hash, m);
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
		maybePostOwnTransactions(newTransactions);

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
		peer=peer.persistState(noveltyHandler);

		// Broadcast latest Belief to connected Peers
		SignedData<Belief> sb = peer.getSignedBelief();
		Message msg = Message.createBelief(sb);

        // at the moment broadcast to all peers trusted or not TODO: recheck this
		manager.broadcast(msg, false);

		// Report transaction results
		long newConsensusPoint = peer.getConsensusPoint();
		if (newConsensusPoint > oldConsensusPoint) {
			log.log(LEVEL_BELIEF, "Consensus update from " + oldConsensusPoint + " to " + newConsensusPoint);
			for (long i = oldConsensusPoint; i < newConsensusPoint; i++) {
				Block block = peer.getPeerOrder().getBlock(i);
				BlockResult br = peer.getBlockResult(i);
				reportTransactions(block, br);
			}
		}

		return true;
	}

	/**
	 * Checks for pending transactions, and if found propose them as a new Block.
	 *
	 * @return True if a new block is published, false otherwise.
	 */
	protected boolean maybePublishBlock() {
		synchronized (newTransactions) {

			int n = newTransactions.size();
			if (n == 0) return false;
			// TODO: smaller block if too many transactions?
			long timestamp = Utils.getCurrentTimestamp();
			Block block = Block.create(timestamp, (List<SignedData<ATransaction>>) newTransactions, peer.getPeerKey());

			ACell.createPersisted(block);

			try {
				Peer newPeer = peer.proposeBlock(block);
				log.log(LEVEL_BELIEF, "New block proposed: " + block.getHash().toHexString());
				newTransactions.clear();
				peer = newPeer;
				return true;
			} catch (BadSignatureException e) {
				// TODO what to do here?
				return false;
			}
		}
	}

	private long lastOwnTransactionTimestamp=0L;

	private static final long OWN_TRANSACTIONS_DELAY=300;
	
	/**
	 * Gets the Peer controller Address
	 * @return Peer controller Address
	 */
	public Address getPeerController() {
		PeerStatus ps=getPeer().getConsensusState().getPeer(getPeerKey());
		if (ps==null) return null;
		return ps.getController();
	}

	/**
	 * Check if the Peer want to send any of its own transactions
	 * @param transactionList List of transactions to add to.
	 */
	private void maybePostOwnTransactions(ArrayList<SignedData<ATransaction>> transactionList) {
		synchronized (transactionList) {
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
				postOwnTransaction(transaction);
			}
		}
	}
	
	private synchronized void postOwnTransaction(ATransaction trans) {
		synchronized (newTransactions) {
			newTransactions.add(getKeyPair().signData(trans));
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
					try {
						beliefs[i++] = newBeliefs.get(addr).getValue();
					} catch (Exception e) {
						log.warning(e.getMessage());
						// Should ignore belief.
					}
				}
				newBeliefs.clear();
			}
			Peer newPeer = peer.mergeBeliefs(beliefs);

			// Check for substantive change (i.e. Orders updated, can ignore timestamp)
			if (newPeer.getBelief().getOrders().equals(peer.getBelief().getOrders())) return false;

			log.log(LEVEL_BELIEF, "New merged Belief update: " + newPeer.getBelief().getHash().toHexString());
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

			Connection pc = m.getPeerConnection();
			log.log(LEVEL_INFO, "Processing status request from: " + pc.getRemoteAddress());
			// log.log(LEVEL_MESSAGE, "Processing query: " + form + " with address: " +
			// address);

			Peer peer=this.getPeer();
			Hash beliefHash=peer.getSignedBelief().getHash();
			Hash stateHash=peer.getStates().getHash();
			Hash initialStateHash=peer.getStates().get(0).getHash();

			AVector<ACell> reply=Vectors.of(beliefHash,stateHash,initialStateHash,getPeerKey());

			pc.sendResult(m.getID(), reply);
		} catch (Throwable t) {
			log.warning("Status Request Error: " + t);
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

			Connection pc = m.getPeerConnection();
			log.log(LEVEL_INFO, "Processing query: " + form + " with address: " + address);
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
				log.warning("Failed to send query result back to client with ID: " + id);
			}

		} catch (Throwable t) {
			log.warning("Query Error: " + t);
		}
	}

	private void processData(Message m) {
		ACell payload = m.getPayload();

		// TODO: be smarter about this? hold a per-client queue for a while?
		Ref<?> r = Ref.get(payload);
		r = r.persistShallow();
		Hash payloadHash = r.getHash();

		log.log(LEVEL_DATA, () -> "Processing DATA of type: " + Utils.getClassName(payload) + " with hash: "
				+ payloadHash.toHexString() + " and encoding: " + Format.encodedBlob(payload).toHexString());

		// if our data satisfies a missing data object, need to process it
		maybeProcessPartial(r.getHash());
	}

	/**
	 * Process an incoming message that represents a Belief
	 *
	 * @param m
	 */
	private void processBelief(Message m) {
		Connection pc = m.getPeerConnection();
		if (pc.isClosed()) return; // skip messages from closed peer

		ACell o = m.getPayload();

		Ref<ACell> ref = Ref.get(o);
		try {
			// check we can persist the new belief
			ref = ref.persist();

			@SuppressWarnings("unchecked")
			SignedData<Belief> signedBelief = (SignedData<Belief>) o;
			signedBelief.validateSignature();

			// TODO: validate trusted connection?
			// TODO: can drop Beliefs if under pressure?

			synchronized (newBeliefs) {
				AccountKey addr = signedBelief.getAccountKey();
				SignedData<Belief> current = newBeliefs.get(addr);
				// Make sure the Belief is the latest from a Peer
				if ((current == null) || (current.getValue().getTimestamp() >= signedBelief.getValue()
						.getTimestamp())) {
					// Add to map of new Beliefs received for each Peer
					newBeliefs.put(addr, signedBelief);

					// Notify the update thread that there is something new to handle
					hasNewMessages=true;
					log.log(LEVEL_BELIEF, "Valid belief received by peer at " + getHostAddress() + ": "
							+ signedBelief.getValue().getHash().toHexString());
				}
			}
		} catch (ClassCastException e) {
			// bad message?
			log.warning("Bad message from peer? " + e.getMessage());
		} catch (BadSignatureException e) {
			// we got sent a bad signature.
			// TODO: Probably need to slash peer? but ignore for now
			log.warning("Bad signed belief from peer: " + Utils.ednString(o));
		}
	}

	/*
	 * Runnable class acting as a peer worker. Handles messages from the receive
	 * queue from known peers
	 */
	private Runnable receiverLoop = new Runnable() {
		@Override
		public void run() {
			Stores.setCurrent(getStore()); // ensure the loop uses this Server's store

			try {
				log.log(LEVEL_SERVER, "Reciever thread started for peer at " + getHostAddress());

				while (isRunning) { // loop until server terminated
					Message m = receiveQueue.poll(100, TimeUnit.MILLISECONDS);
					if (m != null) {
						processMessage(m);
					}
				}

				log.log(LEVEL_SERVER, "Reciever thread terminated normally for peer " + this);
			} catch (InterruptedException e) {
				log.log(LEVEL_SERVER, "Receiver thread interrupted ");
			} catch (Throwable e) {
				log.severe("Receiver thread terminated abnormally! ");
				log.severe("Server FAILED: " + e.getMessage());
				e.printStackTrace();
			} finally {
				// clear thread from Server as we terminate
				receiverThread = null;
			}
		}
	};

	/*
	 * Runnable loop for managing Server state updates
	 */
	private Runnable updateLoop = new Runnable() {
		@Override
		public void run() {
			Stores.setCurrent(getStore()); // ensure the loop uses this Server's store
			try {
				// short initial sleep before we start managing updates. Give stuff time to
				// ramp up.
				Thread.sleep(20);

				// loop while the server is running
				while (isRunning) {

					// Try belief update
					if (maybeUpdateBelief() ) {
						raiseServerChange("consensus");
					}

					// Maybe sleep a bit, wait for some belief updates to accumulate
					if (hasNewMessages) {
						hasNewMessages=false;
					} else {
						try {
							Thread.sleep(SERVER_UPDATE_PAUSE);
						} catch (InterruptedException e) {
							// continue
						}
					}
				}
			} catch (InterruptedException e) {
				log.fine("Terminating Server update due to interrupt");
			} catch (Throwable e) {
				log.severe("Unexpected exception in server update loop: " + e.toString());
				log.severe("Terminating Server update");
				e.printStackTrace();
			} finally {
				// clear thread from Server as we terminate
				updateThread = null;
			}
		}
	};

	private void reportTransactions(Block block, BlockResult br) {
		// TODO: consider culling old interests after some time period
		int nTrans = block.length();
		for (long j = 0; j < nTrans; j++) {
			SignedData<ATransaction> t = block.getTransactions().get(j);
			Hash h = t.getHash();
			Message m = interests.get(h);
			if (m != null) {
				try {
					log.log(LEVEL_INFO, "Returning transaction result to " + m.getPeerConnection().getRemoteAddress());

					Connection pc = m.getPeerConnection();
					if ((pc == null) || pc.isClosed()) continue;
					ACell id = m.getID();
					Result res = br.getResults().get(j).withID(id);
					
					pc.sendResult(res);
				} catch (Throwable e) {
					log.severe("Error sending Result: " + e.getMessage());
					e.printStackTrace();
					// ignore
				}
				interests.remove(h);
			}
		}
	}

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
	 * This will overwrite the previously persisted peer state.
	 */
	public void persistPeerData() {
		AStore tempStore = Stores.current();
		try {
			Stores.setCurrent(store);
			ACell peerData = peer.toData();
			Ref<?> peerRef = ACell.createPersisted(peerData);
			Hash peerHash = peerRef.getHash();
			store.setRootHash(peerHash);
			log.log(LEVEL_INFO, "Stored peer data for Server: " + peerHash.toHexString());
		} catch (Throwable e) {
			log.severe("Failed to persist peer state when closing server: " + e.getMessage());
		} finally {
			Stores.setCurrent(tempStore);
		}
	}

	@Override
	public synchronized void close() {
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
		if (updateThread != null) updateThread.interrupt();
		if (receiverThread != null) receiverThread.interrupt();
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
	 */
	private AKeyPair getKeyPair() {
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

	public void raiseServerChange(String reason) {
		if (event != null) {
			ServerEvent serverEvent = ServerEvent.create(this.getServerInformation(), reason);
			event.onServerChange(serverEvent);
		}
	}

	public ServerInformation getServerInformation() {
		return ServerInformation.create(this);
	}

	public ConnectionManager getConnectionManager() {
		return manager;
	}

	public HashMap<Keyword, Object> getConfig() {
		return config;
	}
}
