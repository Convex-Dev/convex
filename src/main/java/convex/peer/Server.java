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
import convex.core.Order;
import convex.core.Peer;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Blob;
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
 * - A ReceiverThread that precesses message from the Server's receive Queue
 * - An UpdateThreat that handles Belief updates and transaction processing
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

	// Maximum Pause for each iteration of Server connection loop.
	private static final long SERVER_CONNECTION_PAUSE = (1000 * 2);

	private static final Logger log = Logger.getLogger(Server.class.getName());
	private static final Level LEVEL_BELIEF = Level.FINER;
	private static final Level LEVEL_SERVER = Level.FINER;
	private static final Level LEVEL_DATA = Level.FINEST;
	private static final Level LEVEL_PARTIAL = Level.WARNING;
	private static final Level LEVEL_CHALLENGE_RESPONSE = Level.FINEST;
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
	private Consumer<Message> peerReceiveAction = new Consumer<Message>() {
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
	private Thread connectionThread = null;

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
	 * The list of challenges that are being made to remote peers
	 */
	private HashMap<AccountKey, ChallengeRequest> challengeList = new HashMap<>();

	/**
	 * Hostname of the peer server.
	 */
	private String hostname;

	/**
	 * Network Id or the initialStateHash of the network. This is unique to all networks
	 */
	private final Hash networkID;         // or intialStateHash of the network

	private IServerEvent event = null;

	private Server(HashMap<Keyword, Object> config, IServerEvent event) {
		this.event = event;
		AStore configStore = (AStore) config.get(Keywords.STORE);
		this.store = (configStore == null) ? Stores.getGlobalStore() : configStore;

		AKeyPair keyPair = (AKeyPair) config.get(Keywords.KEYPAIR);
		if (keyPair==null) throw new IllegalArgumentException("No Peer Key Pair provided in config");

		// Switch to use the configured store, saving the caller store
		final AStore savedStore=Stores.current();
		try {
			Stores.setCurrent(store);
			this.config = config;
			// now setup the connection manager
			this.manager = new ConnectionManager(config);

			this.peer = establishPeer(keyPair, config);

			nio = NIOServer.create(this, receiveQueue);

			this.networkID=peer.getNetworkID();

		} finally {
			Stores.setCurrent(savedStore);
		}
	}

	private Peer establishPeer(AKeyPair keyPair, Map<Keyword, Object> config2) {
		log.log(LEVEL_INFO, "Establishing Peer with store: "+Stores.current());

		if (Utils.bool(config.get(Keywords.RESTORE))) {
			try {
				Hash hash = store.getRootHash();
				Peer peer = Peer.restorePeer(store, hash, keyPair);
				if (peer != null) return peer;
			} catch (Throwable e) {
				log.warning("Can't restore Peer from store: " + e.getMessage());
			}
		}
		log.log(LEVEL_INFO, "Defaulting to standard Peer startup.");
		return Peer.createStartupPeer(config);
	}

	/**
	 * Creates a Server with a given config. Reference to config is kept: don't
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
	 * Gets the current Belief held by this PeerServer
	 *
	 * @return Current Belief
	 */
	public Peer getPeer() {
		return peer;
	}

	public String getHostname() {
		return hostname;
	}

	public AHashMap<AccountKey, AString> getPeerStatusConnectList() {
		AMap<AccountKey, AString> result = Maps.empty();
		if (getHostname() != null) {
			result = result.assoc(peer.getPeerKey(), Strings.create(getHostname()));
		}
		State state = peer.getConsensusState();
		for ( ABlob key: state.getPeers().keySet()) {
			AccountKey peerKey = RT.ensureAccountKey(key);
			PeerStatus peerStatus = state.getPeer(peerKey);
			AString hostname = peerStatus.getHostname();
			if (hostname != null) {
				result = result.assoc(peerKey, hostname);
			}
		}
		return (AHashMap<AccountKey, AString>) result;
	}

	public synchronized void launch() {
		Object p = config.get(Keywords.PORT);
		Integer port = (p == null) ? null : Utils.toInt(p);

		try {
			nio.launch(port);
			port = nio.getPort(); // get the actual port (may be auto-allocated)


			hostname = String.format("localhost:%d", port);
			if (config.containsKey(Keywords.URL)) {
				hostname = (String) config.get(Keywords.URL);
			}

			// set running status now, so that loops don't terminate
			isRunning = true;

			receiverThread = new Thread(receiverLoop, "Receive queue worker loop serving port: " + port);
			receiverThread.setDaemon(true);
			receiverThread.start();

			updateThread = new Thread(updateLoop, "Server Belief update loop for port: " + port);
			updateThread.setDaemon(true);
			updateThread.start();

			// Close server on shutdown, must be before Etch stores
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

	@SuppressWarnings("unchecked")
	public boolean joinNetwork(AKeyPair keyPair, Address address, String remoteHostname) {
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
		//Hash beliefHash = (Hash) values.get(0);
		//Hash stateHash = (Hash) values.get(1);

		// check the initStateHash to see if this is the network we want to join?
		Hash remoteNetworkID = RT.ensureHash(values.get(2));
		if (!Utils.equals(getNetworkID(),remoteNetworkID)) {
			throw new Error("Failed to join network, we want Network ID "+getNetworkID()+" but remote Peer repoerted "+remoteNetworkID);
		}


		AHashMap<ABlob, AString> buildPeerList = (AHashMap<ABlob, AString>) values.get(3);

		AHashMap<AccountKey, AString> statusPeerList = Maps.empty();

		for ( ABlob key: buildPeerList.keySet()) {
			AccountKey accountKey = RT.ensureAccountKey(key);
			statusPeerList = statusPeerList.assoc(accountKey, buildPeerList.get(key));
		}


		// now use the remote peer host name list returned from the status call
		// to connect to the peers
		connectToPeers(statusPeerList);

		// start connection thread
		connectionThread = new Thread(connectionLoop, "Dynamicaly connect to other peers");
		connectionThread.setDaemon(true);
		connectionThread.start();

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
			case RESPONSE:
				processResponse(m);
				break;
			case RESULT:
				break;
			case TRANSACT:
				processTransact(m);
				break;
			case GOODBYE:
				m.getPeerConnection().close();
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

		// TODO: this should throw MissingDataException?
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

		// published new blocks if needed. Guaranteed to change belief if this happens
		boolean published = maybePublishBlock();

		// only do belief merge if needed: either after publishing a new block or with
		// incoming beliefs
		if ((!published) && newBeliefs.isEmpty()) return false;

		// Update Peer timestamp first. This determines what we might accept.
		peer = peer.updateTimestamp(Utils.getCurrentTimestamp());

		boolean updated = maybeMergeBeliefs();
		if (!updated) return false;

		// Need to check if belief changed from initial state
		// It is possible that incoming beliefs don't change current belief.
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
        // at the moment broadcast to all peers trusted or not
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
			maybePostOwnTransactions(newTransactions);

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

	private long lastOwnTransactionTimestamp=0;
	private static final long OWN_TRANSACTIONS_DELAY=2000;

	/**
	 * Check if the Peer want to send any of its own transactions
	 * @param transactionList List of transactions to add to.
	 */
	private void maybePostOwnTransactions(ArrayList<SignedData<ATransaction>> transactionList) {
		State s=getPeer().getConsensusState();
		long ts=s.getTimeStamp().longValue();

		// If we already did this recently, don't try again
		if (ts<(lastOwnTransactionTimestamp+OWN_TRANSACTIONS_DELAY)) return;

		lastOwnTransactionTimestamp=ts; // mark this timestamp

		String desiredHostname=getHostname(); // Intended hostname
		PeerStatus ps=s.getPeer(getPeerKey());
		AString chn=ps.getHostname();
		String currentHostname=(chn==null)?null:chn.toString();

		// Try to set hostname if not correctly set
		trySetHostname:
		if (!Utils.equals(desiredHostname, currentHostname)) {
			Address address=ps.getOwner();
			if (address==null) break trySetHostname;
			AccountStatus as=s.getAccount(address);
			if (as==null) break trySetHostname;
			if (!Utils.equals(getPeerKey(), as.getAccountKey())) break trySetHostname;

			String code;
			if (desiredHostname==null) {
				code = "(set-peer-data {:url nil})";
			} else {
				code = String.format("(set-peer-data {:url \"%s\"})", desiredHostname);
			}
			ACell message = Reader.read(code);
			ATransaction transaction = Invoke.create(address, as.getSequence()+1, message);
			SignedData<ATransaction> signedTransaction = getKeyPair().signData(transaction);
			transactionList.add(signedTransaction);
		}
	}

	/**
	 * Checks for mergeable remote beliefs, and if found merge and update own
	 * belief.
	 *
	 * @return True if peer Belief was updated, false otherwise.
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
			AHashMap<AccountKey, AString> peerList = getPeerStatusConnectList();

			AVector<ACell> reply=Vectors.of(beliefHash,stateHash,initialStateHash,peerList);

			pc.sendResult(m.getID(), reply);
		} catch (Throwable t) {
			log.warning("Status Request Error: " + t);
		}
	}

	private void processChallenge(Message m) {
		try {
			SignedData<AVector<ACell>> signedData = m.getPayload();
			if ( signedData == null) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "challenge bad message data sent");
				return;
			}
			AVector<ACell> challengeValues = signedData.getValue();

			if (challengeValues == null || challengeValues.size() != 3) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "challenge data incorrect number of items should be 3 not " + challengeValues.size());
				return;
			}
			Connection pc = m.getPeerConnection();
			if ( pc == null) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "No remote peer connection from challenge");
				return;
			}
			log.log(LEVEL_CHALLENGE_RESPONSE, "Processing challenge request from: " + pc.getRemoteAddress());


			// get the token to respond with
			Hash token = RT.ensureHash(challengeValues.get(0));
			if (token == null) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "no challenge token provided");
				return;
			}

			// check to see if we are both want to connect to the same network
			Hash networkId = RT.ensureHash(challengeValues.get(1));
			if (networkId == null || this.networkID == null) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "challenge data has no networkId");
				return;
			}
			if ( !networkId.equals(this.networkID)) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "challenge data has incorrect networkId");
				return;
			}
			// check to see if the challenge is for this peer
			AccountKey toPeer = RT.ensureAccountKey(challengeValues.get(2));
			if (toPeer == null) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "challenge data has no toPeer address");
				return;
			}
			if ( !toPeer.equals(peer.getPeerKey())) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "challenge data has incorrect addressed peer");
				return;
			}

			// get who sent this challenge
			AccountKey fromPeer = signedData.getAccountKey();

			// send the signed response back
			AVector<ACell> responseValues = Vectors.of(token, networkId, fromPeer, signedData.getHash());

			SignedData<ACell> response = peer.sign(responseValues);
			pc.sendResponse(response);

		} catch (Throwable t) {
			log.warning("Challenge Error: " + t);
			// t.printStackTrace();
		}
	}

	private void processResponse(Message m) {
		try {
			SignedData<ACell> signedData = m.getPayload();

			AVector<ACell> responseValues = (AVector<ACell>) signedData.getValue();

			if (responseValues.size() != 4) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "response data incorrect number of items should be 4 not " + responseValues.size());
				return;
			}


			// get the signed token
			Hash token = RT.ensureHash(responseValues.get(0));
			if (token == null) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "no response token provided");
				return;
			}

			// check to see if we are both want to connect to the same network
			Hash networkId = RT.ensureHash(responseValues.get(1));
			if ( networkId == null || !networkId.equals(this.networkID)) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "response data has incorrect networkId");
				return;
			}
			// check to see if the challenge is for this peer
			AccountKey toPeer = RT.ensureAccountKey(responseValues.get(2));
			if ( toPeer == null || !toPeer.equals(peer.getPeerKey())) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "response data has incorrect addressed peer");
				return;
			}

			// hash sent by the response
			Hash challengeHash = RT.ensureHash(responseValues.get(3));

			// get who sent this challenge
			AccountKey fromPeer = signedData.getAccountKey();


			if ( !challengeList.containsKey(fromPeer)) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "response from an unkown challenge");
				return;
			}
			synchronized(challengeList) {

				// get the challenge data we sent out for this peer
				ChallengeRequest challengeRequest = challengeList.get(fromPeer);

				Hash challengeToken = challengeRequest.getToken();
				if (!challengeToken.equals(token)) {
					log.log(LEVEL_CHALLENGE_RESPONSE, "invalid response token sent");
					return;
				}

				AccountKey challengeFromPeer = challengeRequest.getPeerKey();
				if (!signedData.getAccountKey().equals(challengeFromPeer)) {
					log.warning("response key does not match requested key, sent from a different peer");
					return;
				}

				// hash sent by this peer for the challenge
				Hash challengeSourceHash = challengeRequest.getSendHash();
				if ( !challengeHash.equals(challengeHash)) {
					log.log(LEVEL_CHALLENGE_RESPONSE, "response hash of the challenge does not match");
					return;
				}

				Connection pc = m.getPeerConnection();
				// log.log(LEVEL_CHALLENGE_RESPONSE, "Processing response request from: " + pc.getRemoteAddress());

				pc.setTrustedPeerKey(fromPeer);

				// raiseServerMessage(" now trusts peer: " + Utils.toFriendlyHexString(fromPeer.toHexString()));

				// remove from list incase this fails, we can generate another challenge
				challengeList.remove(fromPeer);

			}
			raiseServerChange("trusted connection");

		} catch (Throwable t) {
			log.warning("Response Error: " + t);
		}
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
				if ((current == null) || (current.getValueUnchecked().getTimestamp() >= signedBelief.getValueUnchecked()
						.getTimestamp())) {
					// Add to map of new Beliefs recieved for each Peer
					newBeliefs.put(addr, signedBelief);

					// Notify the update thread that there is something new to handle
					hasNewMessages=true;
				}
			}
			log.log(LEVEL_BELIEF, "Valid belief received by peer at " + getHostAddress() + ": "
					+ signedBelief.getValue().getHash().toHexString());
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
				// short initial sleep before we start managing connections. Give stuff time to
				// ramp up.
				Thread.sleep(10);

				// loop while the server is running
				while (isRunning) {

					// Try belief update
					maybeUpdateBelief();

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

	/*
	 * Runnable loop for managing server connections
	 */
	private Runnable connectionLoop = new Runnable() {
		@Override
		public void run() {
			Stores.setCurrent(getStore()); // ensure the loop uses this Server's store
			try {

				/*
				// wait for server to join network
				while(networkID == null && isRunning) {
					Thread.sleep(2000);
				}
				*/
				// loop while the server is running
				long lastConsensusPoint = 0;
                Order lastOrder = null;
				while (isRunning) {

					Order order=peer.getPeerOrder();
					// TODO: think about behaviour when the Peer leaves or joins. Should Server continue running?
					if (order==null && lastOrder!=null) {
						// raiseServerMessage("is out of sync with the network");
					}
					if (order!=null && lastOrder==null) {
						// System.out.println(getHostname() + " has joined the network");
						///raiseServerMessage("is in sync with the network");
					}
					lastOrder = order;
					if ( lastConsensusPoint != peer.getConsensusPoint()) {
						// only update the peer connection lists if the state has changed
						lastConsensusPoint = peer.getConsensusPoint();
						// raiseServerMessage("reconnect peers");
						connectToPeers(getPeerStatusConnectList());
						// send out a challenge to a peer that is not yet trusted
						// requestChallenges();
						raiseServerChange("consensus change");
					}
					Thread.sleep(SERVER_CONNECTION_PAUSE);
				}
			} catch (InterruptedException e) {
				/* OK? Close the thread normally */
			} catch (Throwable e) {
				log.severe("Unexpected exception, Terminating Server connection loop");
				e.printStackTrace();
			} finally {
				connectionThread = null;
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
		if ((peer != null) && Utils.bool(config.get(Keywords.PERSIST))) {
			persistPeerData();
		}

		isRunning = false;
		if (updateThread != null) updateThread.interrupt();
		if (receiverThread != null) receiverThread.interrupt();
		if (connectionThread != null) connectionThread.interrupt();
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
		return (AKeyPair) config.get(Keywords.KEYPAIR);
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

	public AStore getStore() {
		return store;
	}

	/**
	 * Connects this Peer to a target Peer, adding the Connection to this Server's
	 * Manager
	 *
	 * @param hostname
	 * @param hostAddress
	 * @return The newly created connection
	 * @throws IOException
	 */
	public Connection connectToPeer(AccountKey peerKey, InetSocketAddress hostAddress) throws IOException {
		Connection pc = Connection.connect(hostAddress, peerReceiveAction, getStore());
		manager.setConnection(peerKey, pc);
		return pc;
	}

	protected void connectToPeers(AHashMap<AccountKey, AString>peerList) {
		// connect to the other peers returned from the status call, or in the state list of peers

		for (AccountKey peerKey: peerList.keySet()) {
			AString peerHostname = peerList.get(peerKey);
			if (hostname.toString() == peerHostname.toString() ) {
				continue;
			}
			if ( manager.isConnected(peerKey)) {
				continue;
			}
			try {
				log.log(LEVEL_SERVER, getHostname() + ": connecting too " + peerHostname.toString());
				InetSocketAddress peerAddress = Utils.toInetSocketAddress(peerHostname.toString());
				connectToPeer(peerKey, peerAddress);
			} catch (IOException e) {
				log.warning("cannot connect to peer " + peerHostname.toString());
			}
		}
	}

	/**
	 * Sends out challenges to any connections that are not trusted.
	 *
	 */
	public void requestChallenges() {
		synchronized(challengeList) {
			for (AccountKey peerKey: manager.getConnections().keySet()) {
				Connection connection = manager.getConnection(peerKey);
				if (connection.isTrusted()) {
					continue;
				}
				// skip if a challenge is already being sent
				if (challengeList.containsValue(peerKey)) {
					if (!challengeList.get(peerKey).isTimedout()) {
						// not timed out, then continue to wait
						continue;
					}
					// remove the old timed out request
					challengeList.remove(peerKey);
				}
				ChallengeRequest request = ChallengeRequest.create(peerKey);
				request.send(connection, peer);
				challengeList.put(peerKey, request);
			}
		}
	}

	public void raiseServerChange(String reason) {
		if (event != null) {
			ServerEvent serverEvent = ServerEvent.create(this.getServerInformation(), reason);
			event.onServerChange(serverEvent);
		}
	}

	public Hash getNetworkID() {
		return networkID;
	}

	public ServerInformation getServerInformation() {
		return ServerInformation.create(this, manager);
	}
}
