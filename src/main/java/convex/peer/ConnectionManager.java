package convex.peer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import convex.api.Convex;
import convex.core.Constants;
import convex.core.Peer;
import convex.core.Result;
import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.PeerStatus;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.core.store.Stores;
import convex.core.util.Utils;
import convex.net.Connection;
import convex.net.Message;

/**
 * Class for managing the outbound connections from a Peer Server.
 *
 * Outbound connections need special handling: - Should be trusted connections
 * to known peers - Should be targets for broadcast of belief updates - Should
 * be limited in number
 */
public class ConnectionManager {

	private static final Logger log = Logger.getLogger(ConnectionManager.class.getName());

	static final Level LEVEL_CHALLENGE_RESPONSE = Level.FINEST;
	static final Level LEVEL_CONNECT = Level.INFO;
	
	/**
	 * Pause for each iteration of Server connection loop.
	 */
	static final long SERVER_CONNECTION_PAUSE = 1000;

	protected final Server server;
	private final HashMap<AccountKey,Connection> connections = new HashMap<>();

	/**
	 * Planned future connections for this Peer
	 */
	private final HashSet<InetSocketAddress> plannedConnections=new HashSet<>();
	
	/**
	 * The list of outgoing challenges that are being made to remote peers
	 */
	private HashMap<AccountKey, ChallengeRequest> challengeList = new HashMap<>();

	private Thread connectionThread = null;
	
	private SecureRandom random=new SecureRandom();

	
	/*
	 * Runnable loop for managing server connections
	 */
	private Runnable connectionLoop = new Runnable() {
		@Override
		public void run() {
			Stores.setCurrent(server.getStore()); // ensure the loop uses this Server's store
			try {
				while (true) {
					Thread.sleep(ConnectionManager.SERVER_CONNECTION_PAUSE);				
					makePlannedConnections();			
					maintainConnections();		
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
	
	private void makePlannedConnections() {
		synchronized(plannedConnections) {
			for (InetSocketAddress a: plannedConnections) {
				Connection c=connectToPeer(a);
				if (c==null) {
					log.log(LEVEL_CONNECT, "Planned Connection failed to "+a);
				} else {
					log.log(LEVEL_CONNECT, "Planned Connection made to "+a);
				}
			}
			plannedConnections.clear();
		}
	}


	protected void maintainConnections() {
		State s=server.getPeer().getConsensusState();

		AccountKey[] peers = connections.keySet().toArray(new AccountKey[connections.size()]);
		for (AccountKey p: peers) {
			Connection conn=connections.get(p);
			
			// Remove closed connections
			if ((conn==null)||(conn.isClosed())) {
				connections.remove(p);
				continue;
			}
			
			// Remove Peers not staked in consensus
			PeerStatus ps=s.getPeer(p);
			if ((ps==null)||(ps.getTotalStake()==0)) {
				conn.close();
				connections.remove(p);
			}
		}
		
		// refresh peers list
		peers = connections.keySet().toArray(new AccountKey[connections.size()]);
		
		int targetPeerCount=5;
		if (peers.length<targetPeerCount) {
			// Connect to a random peer with host address by stake
			
			Set<AccountKey> potentialPeers=s.getPeers().keySet();
			InetSocketAddress target=null;
			double accStake=0.0;
			for (ACell c:potentialPeers) {
				AccountKey peerKey=RT.ensureAccountKey(c);
				if (connections.containsKey(peerKey)) continue; // skip if already connected
				PeerStatus ps=s.getPeers().get(peerKey);
				if (ps==null) continue; // skip 
				AString hostName=ps.getHostname();
				if (hostName==null) continue;
				InetSocketAddress maybeAddress=Utils.toInetSocketAddress(hostName.toString());
				if (maybeAddress==null) continue;
				long peerStake=ps.getTotalStake();
				if (peerStake>0) {
					double t=random.nextDouble()*(accStake+peerStake);
					if (t>=accStake) {
						target=maybeAddress;
					}
					accStake+=peerStake;
				}
			}
			
			if (target!=null) {
				connectToPeer(target);
			}
		}
	}


	public ConnectionManager(Server server) {
		this.server = server;
	}

	public synchronized void setConnection(AccountKey peerKey, Connection peerConnection) {
		if (connections.containsKey(peerKey)) {
			connections.get(peerKey).close();
			connections.replace(peerKey, peerConnection);
		}
		else {
			connections.put(peerKey, peerConnection);
		}
	}

	/**
	 * Close and remove the connection
	 *
	 * @param peerKey Peer key linked to the connection to close and remove.
	 *
	 */
	public synchronized void closeConnection(AccountKey peerKey) {
		if (connections.containsKey(peerKey)) {
			connections.get(peerKey).close();
			connections.remove(peerKey);
		}
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


	AccountKey processResponse(Message m, Peer thisPeer) {
		try {
			SignedData<ACell> signedData = m.getPayload();

			@SuppressWarnings("unchecked")
			AVector<ACell> responseValues = (AVector<ACell>) signedData.getValue();

			if (responseValues.size() != 4) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "response data incorrect number of items should be 4 not " + responseValues.size());
				return null;
			}


			// get the signed token
			Hash token = RT.ensureHash(responseValues.get(0));
			if (token == null) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "no response token provided");
				return null;
			}

			// check to see if we are both want to connect to the same network
			Hash networkId = RT.ensureHash(responseValues.get(1));
			if ( networkId == null || !networkId.equals(thisPeer.getNetworkID())) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "response data has incorrect networkId");
				return null;
			}
			// check to see if the challenge is for this peer
			AccountKey toPeer = RT.ensureAccountKey(responseValues.get(2));
			if ( toPeer == null || !toPeer.equals(thisPeer.getPeerKey())) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "response data has incorrect addressed peer");
				return null;
			}

			// hash sent by the response
			Hash challengeHash = RT.ensureHash(responseValues.get(3));

			// get who sent this challenge
			AccountKey fromPeer = signedData.getAccountKey();


			if ( !challengeList.containsKey(fromPeer)) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "response from an unkown challenge");
				return null;
			}
			synchronized(challengeList) {

				// get the challenge data we sent out for this peer
				ChallengeRequest challengeRequest = challengeList.get(fromPeer);

				Hash challengeToken = challengeRequest.getToken();
				if (!challengeToken.equals(token)) {
					log.log(LEVEL_CHALLENGE_RESPONSE, "invalid response token sent");
					return null;
				}

				AccountKey challengeFromPeer = challengeRequest.getPeerKey();
				if (!signedData.getAccountKey().equals(challengeFromPeer)) {
					log.warning("response key does not match requested key, sent from a different peer");
					return null;
				}

				// hash sent by this peer for the challenge
				Hash challengeSourceHash = challengeRequest.getSendHash();
				if ( !challengeHash.equals(challengeSourceHash)) {
					log.log(LEVEL_CHALLENGE_RESPONSE, "response hash of the challenge does not match");
					return null;
				}
				// remove from list incase this fails, we can generate another challenge
				challengeList.remove(fromPeer);

				// return the trusted peer key
				return fromPeer;
			}

		} catch (Throwable t) {
			log.warning("Response Error: " + t);
		}
		return null;
	}



	/**
	 * Sends out challenges to any connections that are not trusted.
	 * @param peer Peer state as basis from which to send challenges
	 *
	 */
	public void requestChallenges(Peer peer) {
		synchronized(challengeList) {
			for (AccountKey peerKey: getConnections().keySet()) {
				Connection connection = getConnection(peerKey);
				if (connection.isTrusted()) {
					continue;
				}
				// skip if a challenge is already being sent
				if (challengeList.containsKey(peerKey)) {
					if (!challengeList.get(peerKey).isTimedout()) {
						// not timed out, then continue to wait
						continue;
					}
					// remove the old timed out request
					challengeList.remove(peerKey);
				}
				ChallengeRequest request = ChallengeRequest.create(peerKey);
				if (request.send(connection, peer)>=0) {
					challengeList.put(peerKey, request);
				} else {
					// TODO: check OK to do nothing and send later?
				}

			}
		}
	}

	/**
	 *
	 * @param msg Message to broadcast
	 *
	 * @param requireTrusted If true, only broadcast to trusted peers
	 *
	 */
	public synchronized void broadcast(Message msg, boolean requireTrusted) {
		for (Connection pc : connections.values()) {
			try {
				if ( (requireTrusted && pc.isTrusted()) || !requireTrusted) {
					pc.sendMessage(msg);
				}
			} catch (IOException e) {
				log.warning("Error in broadcast: " + e.getMessage());
			}
		}
	}

	/**
	 * Connects this Peer to a target Peer, adding the Connection to this Server's
	 * Manager
	 *
	 * @param server TODO
	 * @param peerKey
	 * @param hostAddress
	 * @param trustedPeerKey Peer account key of the trusted peer.
	 * @return The newly created connection
	 * @throws IOException
	 * @throws TimeoutException
	 */
	public Connection connectToPeer(AccountKey peerKey, InetSocketAddress hostAddress, AccountKey trustedPeerKey
	) throws IOException, TimeoutException {
		Connection pc = Connection.connect(hostAddress, server.peerReceiveAction, server.getStore(), trustedPeerKey);
		setConnection(peerKey, pc);
		return pc;
	}
	
	/**
	 * Connects explicitly to a Peer at the given host address
	 * @param hostAddress Address to connect to
	 * @return new Connection, or null if attempt fails
	 */
	public Connection connectToPeer(InetSocketAddress hostAddress) {
		try {
			// Temp client connection
			Convex convex=Convex.connect(hostAddress);
			
			Result status = convex.requestStatus().get(Constants.DEFAULT_CLIENT_TIMEOUT,TimeUnit.MILLISECONDS);
			AVector<ACell> v=status.getValue();
			AccountKey peerKey =RT.ensureAccountKey(v.get(3));
			if (peerKey==null) return null;
			
			Connection existing=connections.get(peerKey);
			if ((existing!=null)&&!existing.isClosed()) return existing;
			
			Connection newConn=convex.getConnection();
			connections.put(peerKey, newConn);
			return newConn;
		} catch (InterruptedException | IOException |ExecutionException | TimeoutException e) {
			return null;
		} 
	}
	
	/**
	 * Schedules a request to connect to a Peer at the given host address
	 * @param hostAddress Address to connect to
	 */
	public void connectToPeerAsync(InetSocketAddress hostAddress) {
		synchronized (plannedConnections) {
			plannedConnections.add(hostAddress);
		}
	}


	protected void connectToPeers(Server server, AHashMap<AccountKey, AString> peerList) {
		// connect to the other peers returned from the status call, or in the state list of peers
	
		for (AccountKey peerKey: peerList.keySet()) {
			AString peerHostname = peerList.get(peerKey);
			if (server.hostname.toString() == peerHostname.toString() ) {
				continue;
			}
			if ( isConnected(peerKey)) {
				continue;
			}
			try {
				log.log(LEVEL_CONNECT, server.getHostname() + ": connecting to " + peerHostname.toString());
				InetSocketAddress peerAddress = Utils.toInetSocketAddress(peerHostname.toString());
				connectToPeer(peerKey, peerAddress, null);
			} catch (IOException | TimeoutException e) {
				Server.log.warning("cannot connect to peer " + peerHostname.toString());
			}
		}
	}

	public void start() {
		// start connection thread
		connectionThread = new Thread(connectionLoop, "Dynamicaly connect to other peers");
		connectionThread.setDaemon(true);
		connectionThread.start();

	}

	public void close() {
		if (connectionThread!=null) {
			connectionThread.interrupt();
		}
	}


}
