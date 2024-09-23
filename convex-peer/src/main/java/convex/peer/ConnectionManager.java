package convex.peer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.UnresolvedAddressException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Belief;
import convex.core.Constants;
import convex.core.Peer;
import convex.core.Result;
import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Keywords;
import convex.core.data.PeerStatus;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.util.LoadMonitor;
import convex.core.util.Utils;
import convex.net.ChallengeRequest;
import convex.net.Connection;
import convex.net.IPUtils;
import convex.net.Message;

/**
 * Class for managing the outbound Peer connections from a Peer Server.
 *
 * Outbound connections need special handling: - Should be trusted connections
 * to known peers - Should be targets for broadcast of belief updates - Should
 * be limited in number
 */
public class ConnectionManager extends AThreadedComponent {

	private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class.getName());

	/**
	 * Pause for each iteration of Server connection loop.
	 */
	static final long SERVER_CONNECTION_PAUSE = 500;

	/**
	 * Default pause for each iteration of Server connection loop.
	 */
	static final long SERVER_POLL_DELAY = 2000;

	/**
	 * How long to wait for a belief poll request of status.
	 */
	static final long POLL_TIMEOUT_MILLIS = 2000;

	/**
	 * How long to wait for a complete acquire of a belief.
	 */
	static final long POLL_ACQUIRE_TIMEOUT_MILLIS = 12000;

	/**
	 * Timeout for a regular Belief delta broadcast
	 */
	private static final long BROADCAST_TIMEOUT = 1000;

	/**
	 * Map of current connections.
	 */
	private final HashMap<AccountKey,Connection> connections = new HashMap<>();

	/**
	 * The list of outgoing challenges that are being made to remote peers
	 */
	private HashMap<AccountKey, ChallengeRequest> challengeList = new HashMap<>();

	private SecureRandom random = new SecureRandom();

	private long pollDelay;

	/**
	 * Celled by the connection manager to ensure we are tracking latest Beliefs on the network
	 * @throws InterruptedException 
	 */
	private void pollBelief() throws InterruptedException {
		try {
			// Poll only if no recent consensus updates
			long lastConsensus = server.getPeer().getConsensusState().getTimestamp().longValue();
			if (lastConsensus + pollDelay >= Utils.getCurrentTimestamp()) return;

			ArrayList<Connection> conns = new ArrayList<>(connections.values());
			if (conns.size() == 0) {
				// Nothing to do
				return;
			}
			
			// TODO: probably shouldn't make a new connection?
			// Maybe use Convex instance instead of Connection?
			Connection c = conns.get(random.nextInt(conns.size()));

			if (c.isClosed()) return;
			;
			try (Convex convex = Convex.connect(c.getRemoteAddress())) {
				// use requestStatusSync to auto acquire hash of the status instead of the value
				Result result=convex.requestStatusSync(POLL_TIMEOUT_MILLIS);
				AVector<ACell> status = result.getValue();

				Hash h=RT.ensureHash(status.get(0));

				Belief sb=(Belief) convex.acquire(h).get(POLL_ACQUIRE_TIMEOUT_MILLIS,TimeUnit.MILLISECONDS);

				server.queueBelief(Message.createBelief(sb));
			} 
		} catch (RuntimeException | TimeoutException | ExecutionException | IOException t) {
			if (server.isLive()) {
				log.warn("Belief Polling failed: {}",t.getClass().toString()+" : "+t.getMessage());
			}
		}
	}

	private long lastConnectionUpdate=Utils.getCurrentTimestamp();
	
	protected void maintainConnections() throws InterruptedException {
		State s=server.getPeer().getConsensusState();

		long now=Utils.getCurrentTimestamp();
		long millisSinceLastUpdate=Math.max(0,now-lastConnectionUpdate);

		int targetPeerCount=getTargetPeerCount();
		int currentPeerCount=connections.size();
		double totalStake=s.computeStakes().get(null);

		AccountKey[] peers = connections.keySet().toArray(new AccountKey[currentPeerCount]);
		for (AccountKey p: peers) {
			Connection conn=connections.get(p);

			// Remove closed connections. No point keeping these
			if ((conn==null)||(conn.isClosed())) {
				closeConnection(p);
				currentPeerCount--;
				continue;
			}

			/*
			 *  Always remove Peers not staked in consensus. This should eliminate Peers that have
			 *  withdrawn, have trivial stake or are slashed from current consideration.
			 */
			PeerStatus ps=s.getPeer(p);
			if ((ps==null)||(ps.getTotalStake()<=Constants.MINIMUM_EFFECTIVE_STAKE)) {
				closeConnection(p);
				currentPeerCount--;
				continue;
			}

			/* Drop Peers randomly if they have a small stake
			 * This ensure that new peers will get picked up occasionally and
			 * the distribution of peers tends towards the level of stake over time
			 */
			if ((millisSinceLastUpdate>0)&&(currentPeerCount>=targetPeerCount)) {
				double prop=ps.getTotalStake()/totalStake; // proportion of stake represented by this Peer
				// Very low chance of dropping a Peer with high stake (more than
				double keepChance=Math.min(1.0, prop*targetPeerCount);

				if (keepChance<1.0) {

					double dropRate=millisSinceLastUpdate/(double)Config.PEER_CONNECTION_DROP_TIME;
					if (random.nextDouble()<(dropRate*(1.0-keepChance))) {
						closeConnection(p);
						currentPeerCount--;
						continue;
					}
				}
			}

			// send request for a trusted peer connection if necessary
			// TODO: need to find out why the response message is not being received by the peers
			requestChallenge(p, conn, server.getPeer());
		}

		// refresh peers list
		currentPeerCount=connections.size();
		if (currentPeerCount<targetPeerCount) {
			tryRandomConnect(s);
		}
		
		lastConnectionUpdate=Utils.getCurrentTimestamp();
	}

	private void tryRandomConnect(State s) throws InterruptedException {
		// Connect to a random peer with host address by stake
		// SECURITY: stake weighted connection is important to avoid bad peers
		// influencing the connection pool

		Set<AccountKey> potentialPeers=s.getPeers().keySet();
		InetSocketAddress target=null;
		double accStake=0.0;
		for (ACell c:potentialPeers) {
			AccountKey peerKey=RT.ensureAccountKey(c);
			if (connections.containsKey(peerKey)) continue; // skip if already connected

			if (server.getPeerKey().equals(peerKey)) continue; // don't connect to self!!

			PeerStatus ps=s.getPeers().get(peerKey);
			if (ps==null) continue; // skip
			AString hostName=ps.getHostname();
			if (hostName==null) continue;
			InetSocketAddress maybeAddress=IPUtils.toInetSocketAddress(hostName.toString());
			if (maybeAddress==null) continue;
			long peerStake=ps.getPeerStake();
			if (peerStake>Constants.MINIMUM_EFFECTIVE_STAKE) {
				double t=random.nextDouble()*(accStake+peerStake);
				if (t>=accStake) {
					target=maybeAddress;
				}
				accStake+=peerStake;
			}
		}

		if (target!=null) {
			// Try to connect to Peer. If it fails, no worry, will retry another peer next time
			boolean success=connectToPeer(target) != null;
			if (!success) {
				log.warn("Failed to connect to Peer at "+target);
			}
		}
	}

	/**
	 * Gets the desired number of outgoing connections
	 * @return
	 */
	private int getTargetPeerCount() {
		Integer target;
		try {
			target = Utils.toInt(server.getConfig().get(Keywords.OUTGOING_CONNECTIONS));
		} catch (IllegalArgumentException ex) {
			target=null;
		}
		if (target==null) target=Config.DEFAULT_OUTGOING_CONNECTION_COUNT;
		return target;
	}


	public ConnectionManager(Server server) {
		super(server);

	}

	/**
	 * Close and remove a connection
	 *
	 * @param peerKey Peer key linked to the connection to close and remove.
	 *
	 */
	public void closeConnection(AccountKey peerKey) {
		Connection conn=connections.get(peerKey);
		if (conn!=null) {
			conn.close();
			connections.remove(peerKey);
		}
	}

	/**
	 * Close all outgoing connections from this Peer
	 */
	public void closeAllConnections() {
		for (Connection conn:connections.values()) {
			if (conn!=null) conn.close();
		}
		connections.clear();
	}

	/**
	 * Gets the current set of outbound peer connections from this server
	 *
	 * @return Set of connections
	 */
	public HashMap<AccountKey,Connection> getConnections() {
		return connections;
	}

	/**
	 * Return true if a specified Peer is connected
	 * @param peerKey Public Key of Peer
	 * @return True if connected
	 *
	 */
	public boolean isConnected(AccountKey peerKey) {
		return connections.containsKey(peerKey);
	}


	/**
	 * Gets a connection based on the peers public key
	 * @param peerKey Public key of Peer
	 *
	 * @return Connection instance, or null if not found
	 */
	public Connection getConnection(AccountKey peerKey) {
		if (!connections.containsKey(peerKey)) return null;
		return connections.get(peerKey);
	}

	/**
	 * Returns the number of active connections
	 * @return Number of connections
	 */
	public int getConnectionCount() {
		return connections.size();
	}

	/**
	 * Returns the number of trusted connections
	 * @return Number of trusted connections
	 *
	 */
	public int getTrustedConnectionCount() {
		int result = 0;
		for (Connection connection : connections.values()) {
			if (connection.isTrusted()) {
				result ++;
			}
		}
		return result;
	}

	public void processChallenge(Message m, Peer thisPeer) {
		SignedData<AVector<ACell>> signedData=null;
		try {
			signedData = m.getPayload();
			if ( signedData == null) {
				throw new BadFormatException("null challenge?");
			}
		} catch (BadFormatException e) {
			alertBadMessage(m,"Bad format in challenge");
			return;
		}
		
		AVector<ACell> challengeValues = signedData.getValue();

		if (challengeValues == null || challengeValues.size() != 3) {
			log.debug("challenge data incorrect number of items should be 3 not ",RT.count(challengeValues));
			return;
		}
		
		// log.log(LEVEL_CHALLENGE_RESPONSE, "Processing challenge request from: " + pc.getRemoteAddress());

		// get the token to respond with
		Hash token = RT.ensureHash(challengeValues.get(0));
		if (token == null) {
			log.warn( "no challenge token provided");
			return;
		}

		// check to see if we are both want to connect to the same network
		Hash networkId = RT.ensureHash(challengeValues.get(1));
		if (networkId == null) {
			log.warn( "challenge data has no networkId");
			return;
		}
		if ( !networkId.equals(thisPeer.getNetworkID())) {
			log.warn( "challenge data has incorrect networkId");
			return;
		}
		// check to see if the challenge is for this peer
		AccountKey toPeer = RT.ensureAccountKey(challengeValues.get(2));
		if (toPeer == null) {
			log.warn( "challenge data has no toPeer address");
			return;
		}
		if ( !toPeer.equals(thisPeer.getPeerKey())) {
			log.warn( "challenge data has incorrect addressed peer");
			return;
		}

		// get who sent this challenge
		AccountKey fromPeer = signedData.getAccountKey();

		// send the signed response back
		AVector<ACell> responseValues = Vectors.of(token, thisPeer.getNetworkID(), fromPeer, signedData.getHash());

		SignedData<ACell> response = thisPeer.sign(responseValues);
		// log.log(LEVEL_CHALLENGE_RESPONSE, "Sending response to "+ pc.getRemoteAddress());
		Message resp=Message.createResponse(response);
		m.returnMessage(resp);
	}

	/**
	 * Processes a response message received from a peer
	 * @param m
	 * @param thisPeer
	 * @return
	 */
	AccountKey processResponse(Message m, Peer thisPeer) {
		SignedData<ACell> signedData;
		try {
			signedData = m.getPayload();
		} catch (BadFormatException e) {
			return null;
		}

		log.debug( "Processing response request");

		@SuppressWarnings("unchecked")
		AVector<ACell> responseValues = (AVector<ACell>) signedData.getValue();

		if (responseValues.size() != 4) {
			log.warn( "response data incorrect number of items should be 4 not {}",responseValues.size());
			return null;
		}


		// get the signed token
		Hash token = RT.ensureHash(responseValues.get(0));
		if (token == null) {
			log.warn( "no response token provided");
			return null;
		}

		// check to see if we are both want to connect to the same network
		Hash networkId = RT.ensureHash(responseValues.get(1));
		if ( networkId == null || !networkId.equals(thisPeer.getNetworkID())) {
			log.warn( "response data has incorrect networkId");
			return null;
		}
		// check to see if the challenge is for this peer
		AccountKey toPeer = RT.ensureAccountKey(responseValues.get(2));
		if ( toPeer == null || !toPeer.equals(thisPeer.getPeerKey())) {
			log.warn( "response data has incorrect addressed peer");
			return null;
		}

		// hash sent by the response
		Hash challengeHash = RT.ensureHash(responseValues.get(3));

		// get who sent this challenge
		AccountKey fromPeer = signedData.getAccountKey();


		if ( !challengeList.containsKey(fromPeer)) {
			log.warn( "response from an unkown challenge");
			return null;
		}
		
		synchronized(challengeList) {

			// get the challenge data we sent out for this peer
			ChallengeRequest challengeRequest = challengeList.get(fromPeer);

			Hash challengeToken = challengeRequest.getToken();
			if (!challengeToken.equals(token)) {
				log.warn( "invalid response token sent");
				return null;
			}

			AccountKey challengeFromPeer = challengeRequest.getPeerKey();
			if (!signedData.getAccountKey().equals(challengeFromPeer)) {
				log.warn("response key does not match requested key, sent from a different peer");
				return null;
			}

			// hash sent by this peer for the challenge
			Hash challengeSourceHash = challengeRequest.getSendHash();
			if ( !challengeHash.equals(challengeSourceHash)) {
				log.warn("response hash of the challenge does not match");
				return null;
			}
			// remove from list incase this fails, we can generate another challenge
			challengeList.remove(fromPeer);

			Connection connection = getConnection(fromPeer);
			if (connection != null) {
				connection.setTrustedPeerKey(fromPeer);
			}

			// return the trusted peer key
			return fromPeer;
		}
	}



	/**
	 * Sends out a challenge to a connection that is not trusted.
	 * @param toPeerKey Peer key that we need to send the challenge too.
	 * @param connection untrusted connection
	 * @param thisPeer Source peer that the challenge is issued from
	 *
	 */
	public void requestChallenge(AccountKey toPeerKey, Connection connection, Peer thisPeer) {
		synchronized(challengeList) {
			if (connection.isTrusted()) {
				return;
			}
			// skip if a challenge is already being sent
			if (challengeList.containsKey(toPeerKey)) {
				if (!challengeList.get(toPeerKey).isTimedout()) {
					// not timed out, then continue to wait
					return;
				}
				// remove the old timed out request
				challengeList.remove(toPeerKey);
			}
			ChallengeRequest request = ChallengeRequest.create(toPeerKey);
			if (request.send(connection, thisPeer)>=0) {
				challengeList.put(toPeerKey, request);
			} else {
				// TODO: check OK to do nothing and send later?
			}
		}
	}

	/**
	 * Broadcasts a Message to all connected Peers
	 * 
	 * @param msg Message to broadcast
	 * @throws InterruptedException If broadcast is interrupted
	 *
	 */
	public void broadcast(Message msg) throws InterruptedException {
		HashMap<AccountKey,Connection> hm=getCurrentConnections();
		
		long start=Utils.getCurrentTimestamp();
		while ((!hm.isEmpty())&&(start+BROADCAST_TIMEOUT>Utils.getCurrentTimestamp())) {
			ArrayList<Map.Entry<AccountKey,Connection>> left=new ArrayList<>(hm.entrySet());
			
			// Shuffle order for sending
			Utils.shuffle(left);
			
			for (Map.Entry<AccountKey,Connection> me: left) {
				Connection pc=me.getValue();
				boolean sent;
				try {
					sent = pc.sendMessage(msg);
				} catch (IOException e) {
					// Something went wrong, lose this connection
					closeConnection(me.getKey());
					sent=false;
				}
				if (sent) {
					hm.remove(me.getKey());	
				} else {
					// log.warn("Broadcast failed!");
				}
			}
			
			// terminate loop if everything is successfully sent
			if (hm.isEmpty()) break;
			
			// Avoid a busy wait if buffers are full and still have things to send		
			LoadMonitor.down();
			Thread.sleep(10);
			LoadMonitor.up();
		}
		
		if ((!hm.isEmpty())&&server.isLive()) {
			ArrayList<Map.Entry<AccountKey,Connection>> left=new ArrayList<>(hm.entrySet());
			Map.Entry<AccountKey,Connection> drop=left.get(random.nextInt(left.size()));
			AccountKey dropKey=drop.getKey();
			closeConnection(dropKey);
			log.warn("Unable to send broadcast to "+hm.size()+" peers, dropped one connection to: "+dropKey);
		}
	}

	private HashMap<AccountKey, Connection> getCurrentConnections() {
		synchronized(connections) {
			return new HashMap<>(connections);
		}
	}

	/**
	 * Connects explicitly to a Peer at the given host address
	 * @param hostAddress Address to connect to
	 * @return new Connection, or null if attempt fails
	 * @throws InterruptedException 
	 */
	public Connection connectToPeer(InetSocketAddress hostAddress) throws InterruptedException {
		Connection newConn = null;
		try {
			// Use temp client connection to query status
			Convex convex=Convex.connect(hostAddress);
			Result result = convex.requestStatusSync(Config.DEFAULT_CLIENT_TIMEOUT);
			if (result.isError()) {
				log.info("Bad status message from remote Peer");
				return null;
			}
			
			AVector<ACell> status = result.getValue();
			// close the temp connection to Convex API
			convex.close();
			
		

			AccountKey peerKey =RT.ensureAccountKey(status.get(3));
			if (peerKey==null) return null;

			Connection existing=connections.get(peerKey);
			if ((existing!=null)&&!existing.isClosed()) return existing;
			synchronized(connections) {
				// reopen with connection to the peer and handle server messages
				newConn = Connection.connect(hostAddress, server.receiveAction, server.getStore(), null,Config.SOCKET_PEER_BUFFER_SIZE,Config.SOCKET_PEER_BUFFER_SIZE);
				connections.put(peerKey, newConn);
			}
		} catch (IOException | TimeoutException e) {
			// ignore any errors from the peer connections
			return null;
		} catch (UnresolvedAddressException e) {
			log.info("Unable to resolve host address: "+hostAddress);
			return null;
		}
		return newConn;
	}

	@Override
	public void close() {
		// broadcast GOODBYE message to all outgoing remote peers
		try {
			Message msg = Message.createGoodBye();
			broadcast(msg);
		} catch (InterruptedException e ) {
			// maintain interrupt status
			Thread.currentThread().interrupt();
		} finally {
			super.close();
		}
	}
	
	@Override
	public void start() {
		Object _pollDelay = server.getConfig().get(Keywords.POLL_DELAY);
		this.pollDelay = (_pollDelay == null) ? ConnectionManager.SERVER_POLL_DELAY : Utils.toInt(_pollDelay);

		super.start();
	}

	@Override
	protected void loop() throws InterruptedException {
		LoadMonitor.down();
		Thread.sleep(ConnectionManager.SERVER_CONNECTION_PAUSE);
		LoadMonitor.up();
		maintainConnections();
		pollBelief();
	}

	@Override
	protected String getThreadName() {
		return "Connection Manager thread at "+server.getPort();
	}

	/**
	 * Called to signal a bad / corrupt message from a Peer.
	 * @param m Message of concern 
	 * @param reason Reason message considered bad
	 */
	public void alertBadMessage(Message m, String reason) {
		// TODO Possibly dump Peer? Send a result indicating bad message?
		log.warn(reason);
	}

	/**
	 * Called to signal missing data in a Belief / Order
	 * @param m Message which caused alert
	 * @param e Missing data exception encountered
	 * @param peerKey Peer key which triggered missing data
	 */
	public void alertMissing(Message m, MissingDataException e, AccountKey peerKey) {
		if (log.isDebugEnabled()) {
			String message= "Missing data "+e.getMissingHash();
			log.debug(message);
		}
		
		// TODO: possibly fire off request to specific Peer? Unclear if this improves things generally, but might avoid polling
		
	}

}
