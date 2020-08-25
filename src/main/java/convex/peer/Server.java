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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import convex.core.Belief;
import convex.core.Block;
import convex.core.BlockResult;
import convex.core.ErrorCodes;
import convex.core.Init;
import convex.core.Peer;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Hash;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.Context;
import convex.core.lang.impl.AExceptional;
import convex.core.lang.impl.ErrorValue;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.util.Utils;
import convex.net.Connection;
import convex.net.Message;
import convex.net.MessageType;
import convex.net.NIOServer;

/**
 * A self contained server that can be launched with a config.
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

	private static final int RECEIVE_QUEUE_SIZE = 256;

	// Pause for each iteration of manager loop.
	private static final long MANAGER_PAUSE = 10L;

	// Pause before merging.
	// Higher pause means more latency, but potentially reduces bandwidth by
	// aggregating more belief updates
	private static final long MERGE_PAUSE = 10L;

	private static final Logger log = Logger.getLogger(Server.class.getName());
	private static final Level LEVEL_BELIEF = Level.FINER;
	private static final Level LEVEL_SERVER = Level.FINER;
	private static final Level LEVEL_DATA = Level.FINEST;
	private static final Level LEVEL_PARTIAL = Level.WARNING;
	// private static final Level LEVEL_MESSAGE = Level.FINER;

	private BlockingQueue<Message> receiveQueue = new ArrayBlockingQueue<Message>(RECEIVE_QUEUE_SIZE);

	/**
	 * Message consumer that simply enqueues received messages
	 * Used for outward connections. i.e. ones this Server has made.
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

	private final AStore store;
	private final Map<Keyword, Object> config;

	private boolean running = false;

	private NIOServer nio;

	/** The Peer instance current state of this server */
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
	private HashMap<Hash,Message> partialMessages = new HashMap<Hash,Message>();

	/**
	 * The list of new beliefs received from remote peers the block being created
	 * Should only modify with the lock for this Server help.
	 */
	private HashMap<Address, SignedData<Belief>> newBeliefs = new HashMap<>();

	private Server(Map<Keyword, Object> config) {
		this.config = config;
		this.manager = new ConnectionManager(config);
		this.peer = Peer.createStartupPeer(config);
		AStore configStore = (AStore) config.get(Keywords.STORE);

		store = (configStore == null) ? Stores.DEFAULT : configStore;
		nio = NIOServer.create(this, receiveQueue);
	}

	public static Server create(Map<Keyword, Object> config) {
		return new Server(new HashMap<>(config));
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

	public void launch() {
		Object p = config.get(Keywords.PORT);
		Integer port = (p == null) ? null : Utils.toInt(p);

		close(); // in case of relaunch? close first.
		try {
			nio.launch();

			// set running status now, so that loops don't terminate
			running = true;

			new Thread(receiverLoop, "Receive worker loop serving port: " + port).start();
			new Thread(updateLoop, "Server management loop for port: " + port).start();
			log.log(LEVEL_SERVER, "Peer Server started with Peer Address: " + getAddress().toChecksumHex());
		} catch (Exception e) {
			throw new Error("Failed to launch Server on port: " + port, e);
		}
	}

	/**
	 * Process a message received from a peer. We know at this point that the
	 * message parsed successfully, not much else.....
	 * 
	 * If the message is partial, will be queued pending delivery of missing data
	 * 
	 * @param m
	 */
	private void processMessage(Message m) {
		MessageType type = m.getType();
		log.warning(m.toString());
		try {
			switch (type) {
			case BELIEF:
				processBelief(m);
				break;
			case CHALLENGE:
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
				break;
			case RESULT:
				break;
			case TRANSACT:
				processTransact(m);
				break;
			case GOODBYE:
				m.getPeerConnection().close();
				break;
			}
		} catch (MissingDataException e) {
			Hash missingHash = e.getMissingHash();
			log.log(LEVEL_PARTIAL,"Missing data: " + missingHash.toHexString() + " in message of type " + type);
			try {
				registerPartialMessage(missingHash,m);
				m.getPeerConnection().sendMissingData(missingHash);
				log.log(LEVEL_PARTIAL,()->"Requested missing data "+missingHash.toHexString()+ " for partial message");
			} catch (IOException ex) {
				log.log(Level.WARNING,()->"Exception while requesting missing data: "+ex.getMessage());
			}
		} catch (BadFormatException|ClassCastException|NullPointerException e) {
			log.warning("Error processing client message: " + e);
		}
	}

	/**
	 * Respond to a request for missing data, on a best-efforts basis. 
	 * Requests for missing data we do not hold are ignored.
	 * 
	 * @param m
	 * @throws BadFormatException 
	 */
	private void processMissingData(Message m) throws BadFormatException {
		// payload for a missing data request should be a valid Hash
		Hash h = m.getPayload();
		if (h==null) throw new BadFormatException("Hash required for missing data message");
		
		Ref<?> r = store.refForHash(h);
		if (r != null) {
			try {
				Object data=r.getValue();
				m.getPeerConnection().sendData(data);
				log.info(() -> "Sent missing data for hash: " + h.toHexString() + " with type "+Utils.getClassName(data));
			} catch (IOException e) {
				log.info(() -> "Unable to deliver missing data for " + h.toHexString() + " due to: "+e.getMessage());
			}
		} else {
			log.warning(() -> "Unable to provide missing data for " + h.toHexString() + " from store: "+Stores.current());
		}
	}

	@SuppressWarnings("unchecked")
	private void processTransact(Message m) {
		// query is a vector [id , signed-object]
		AVector<Object> v = m.getPayload();
		SignedData<ATransaction> sd = (SignedData<ATransaction>) v.get(1);

		// TODO: this should throw MissingDataException?
		Ref.createPersisted(sd);
		
		if (!sd.checkSignature()) {
			// terminate the connection, dishonest peer.
			try {
				m.getPeerConnection().sendResult(m.getID(), "Bad Signature!", ErrorCodes.SIGNATURE);
			} catch (IOException e) {
				// Ignore??
			}
			log.warning("Bad signature from Client! "+sd);
			return;
		}

		synchronized (newTransactions) {
			newTransactions.add(sd);
			registerInterest(sd.getHash(), m);
		}
	}
	
	/**
	 * Checks if received data fulfils the requirement for a partial message
	 * If so, process the message again.
	 * 
	 * @param hash
	 * @return true if the data request resulted in a re-queued message, false otherwise
	 */
	private boolean maybeProcessPartial(Hash hash) {
		Message m;
		synchronized (partialMessages) {
			m=partialMessages.get(hash);
			
			if (m!=null) {
				log.log(LEVEL_PARTIAL,()->"Attempting to re-queue partial message due to received hash: "+hash.toHexString());
				if (receiveQueue.offer(m)) {
					partialMessages.remove(hash);
					return true;
				} else {
					log.log(Level.WARNING,()->"Queue full for message with received hash: "+hash.toHexString());
				}
			}
		}
		return false;
 	}

	/**
	 * Stores a partial message for potential later handling.
	 * @param missingHash Hash of missing data dependency
	 * @param m Message to re-attempt later when missing data is received.
	 */
	private void registerPartialMessage(Hash missingHash, Message m) {
		synchronized (partialMessages) {
			log.log(LEVEL_PARTIAL,()->"Registering partial message with missing hash: "+missingHash);
			partialMessages.put(missingHash,m);
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
	 * Handle general Belief update
	 * 
	 * @throws InterruptedException
	 */
	protected boolean maybeUpdateBelief() throws InterruptedException {
		long oldConsensusPoint = peer.getConsensusPoint();

		Belief initialBelief = peer.getBelief();

		// published new blocks if needed. Guaranteed to change belief if this happens
		boolean published = maybePublishBlock();

		// only do belief merge if needed: either after publishing a new block or with
		// incoming beliefs
		if ((!published) && newBeliefs.isEmpty()) return false;

		Thread.sleep(MERGE_PAUSE);

		maybeMergeBeliefs();

		// Need to check if belief changed from initial state
		// It is possible that incoming beliefs don't change current belief.
		final Belief belief = peer.getBelief();
		if (belief == initialBelief) return false;

		// At this point we know something updated our belief, so we want to rebroadcast belief to network
		Consumer<Ref<ACell>> noveltyHandler= r -> {
			Object o = r.getValue();
			if (o==belief) return; // skip sending data for belief cell itself, will be BELIEF payload
			Message msg = Message.createData(o);
			manager.broadcast(msg);
		};
		
		Ref.createAnnounced(belief, noveltyHandler);
		SignedData<Belief> sb = peer.getSignedBelief();
		Message msg = Message.createBelief(sb);
		manager.broadcast(msg);

		long newConsensusPoint = peer.getConsensusPoint();
		if (newConsensusPoint > oldConsensusPoint) {
			log.log(LEVEL_BELIEF, "Consenus update from " + oldConsensusPoint + " to " + newConsensusPoint);
			for (long i = oldConsensusPoint; i < newConsensusPoint; i++) {
				Block block = peer.getPeerOrder().getBlock(i);
				BlockResult br = peer.getResult(i);
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
			Block block = Block.create(timestamp, (List<SignedData<ATransaction>>) newTransactions,peer.getAddress());

			Ref.createPersisted(block);

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

	/**
	 * Checks for mergeable remote beliefs, and if found merge and update own
	 * belief.
	 * 
	 * @return True if peer Belief was updated, false otherwise.
	 */
	protected boolean maybeMergeBeliefs() {
		synchronized (newBeliefs) {
			int n = newBeliefs.size();

			try {
				Belief[] beliefs = new Belief[n];
				int i = 0;
				for (Address addr : newBeliefs.keySet()) {
					try {
						beliefs[i++] = newBeliefs.get(addr).getValue();
					} catch (Exception e) {
						log.warning(e.getMessage());
						// Should ignore belief.
					}
				}
				newBeliefs.clear();

				Peer newPeer = peer.mergeBeliefs(beliefs);
				if (newPeer.getBelief() == peer.getBelief()) return false;

				log.log(LEVEL_BELIEF, "New merged Belief update: " + newPeer.getBelief().getHash().toHexString());
				// we merged successfully, so clear pending beliefs and update Peer
				peer = newPeer;
				return true;
			} catch (MissingDataException e) {
				// Shouldn't happen if beliefs are persisted
				// e.printStackTrace();
				throw new Error("Missing data in belief update: "+e.getMissingHash().toHexString(), e);
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
	}

	private void processQuery(Message m) {
		// query is a vector [id , form, address?]
		AVector<Object> v = m.getPayload();
		Long id = (Long) v.get(0);
		Object form = v.get(1);
		
		// extract the Address, or use HERO if not available.
		Address address = (v.count() > 2) ? (Address) v.get(2) : Init.HERO; // optional address
		if (address==null) address=Init.HERO;
		
		Connection pc = m.getPeerConnection();
		try {
			log.info("Processing query: " + form + " with address: " + address);
			// log.log(LEVEL_MESSAGE, "Processing query: " + form + " with address: " + address);
			Context<?> result = peer.executeQuery(form, address);
			if (result.isExceptional()) {
				AExceptional err = result.getExceptional();
				Object code=err.getCode();
				Object message=(err instanceof ErrorValue)?((ErrorValue)err).getMessage():err.toString();
				pc.sendResult(id, message, code);
			} else {
				pc.sendResult(id, result.getResult());
			}

		} catch (Throwable t) {
			log.warning("Query Error: " + form + " :: " + t);
		}
	}

	private void processData(Message m) {
		Object payload=m.getPayload();
		
		// TODO: be smarter about this? hold a per-client queue for a while?
		Ref<?> r=Ref.create(payload).persistShallow();
		r=r.persistShallow();
		Hash payloadHash=r.getHash();

		log.log(LEVEL_DATA,()->"Processing DATA of type: "+Utils.getClassName(payload)+ " with hash: "+payloadHash.toHexString() +" and encoding: "+Format.encodedBlob(payload).toHexString());

		// if our data satisfies a missing data object, need to process it
		maybeProcessPartial(r.getHash());
	}



	private void processBelief(Message m) {
		Connection pc = m.getPeerConnection();
		if (pc.isClosed()) return; // skip messages from closed peer

		Object o = m.getPayload();

		Ref<Object> ref = Ref.create(o);
		try {
			// check we can persist the new belief
			ref=ref.persist();
			
			@SuppressWarnings("unchecked")
			SignedData<Belief> signedBelief = (SignedData<Belief>) o;
			signedBelief.validateSignature();

			synchronized (newBeliefs) {
				Address addr = signedBelief.getAddress();
				SignedData<Belief> current = newBeliefs.get(addr);
				if ((current == null) || (current.getValueUnchecked().getTimestamp() >= signedBelief.getValueUnchecked()
						.getTimestamp())) {
					newBeliefs.put(addr, signedBelief);
				}
			}
			log.log(LEVEL_BELIEF, "Valid belief received by peer at "+getHostAddress()+ ": " + signedBelief.getValue().getHash().toHexString());
		} catch (ClassCastException e) {
			// bad message?
			log.warning("Bad message from peer? "+e.getMessage());
		} catch (BadSignatureException e) {
			// we got sent a bad signature.
			// TODO: Probably need to slash peer? but ignore for now
			log.warning("Bad signed belief from peer: "+Utils.ednString(o));
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
				while (running) { // loop until server terminated
					Message m = receiveQueue.poll(100, TimeUnit.MILLISECONDS);
					if (m != null) {
						processMessage(m);
					}
				}
				log.log(LEVEL_SERVER, "Reciever thread terminated normally for peer " + this);
			} catch (Throwable e) {
				log.severe("Receiver thread terminated abnormally! " + e.getMessage());
				e.printStackTrace();
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
				while (running) {
					try {
						// sleep a bit, wait for transactions to accumulate
						Thread.sleep(Server.MANAGER_PAUSE);
	
						// Update Peer timestamp first. This determines what we might accept.
						peer = peer.updateTimestamp(Utils.getCurrentTimestamp());
	
						// Try belief update
						maybeUpdateBelief();
					} catch (Throwable e) {
						log.severe("Unexpected exception in server update loop: "+e.getMessage());
						e.printStackTrace();
					}
				}
			} catch (InterruptedException e) {
				log.warning("Server manager loop interrupted?");
				Thread.currentThread().interrupt();
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
					log.info("Returning transaction result to "+m.getPeerConnection().getRemoteAddress());
					
					Connection pc = m.getPeerConnection();
					if ((pc == null) || pc.isClosed()) continue;
					ErrorValue err = br.getError(j);
					if (err == null) {
						Object result = br.getResult(j);
						pc.sendResult(m.getID(), result);
					} else {
						pc.sendResult(m.getID(), err.getMessage(), err.getCode());
					}
				} catch (Exception e) {
					System.err.println("Error sending Result: "+e.getMessage());
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

	@Override
	public void close() {
		running = false;
		nio.close();
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
	 * Gets the address of the peer account
	 * 
	 * @return Address of this Peer
	 */
	public Address getAddress() {
		AKeyPair kp = getKeyPair();
		if (kp == null) return null;
		return kp.getAddress();
	}

	/**
	 * Connects this Peer to a target Peer, adding the Connection to this Server's Manager
	 * @param hostAddress
	 * @return The newly created connection
	 * @throws IOException
	 */
	public Connection connectToPeer(InetSocketAddress hostAddress) throws IOException {
		Connection pc = Connection.connect(hostAddress, peerReceiveAction,getStore());
		manager.addConnection(pc);
		return pc;
	}

	public AStore getStore() {
		return store;
	}
}
