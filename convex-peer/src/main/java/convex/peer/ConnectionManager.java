package convex.peer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.UnresolvedAddressException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.CPoSConstants;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.data.AArrayBlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.util.LoadMonitor;
import convex.core.util.Utils;
import convex.net.IPUtils;

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
	 * Map of current connections.
	 */
	private final HashMap<AccountKey,Convex> connections = new HashMap<>();

	private SecureRandom random = new SecureRandom();

	private long pollDelay;

	/**
	 * Celled by the connection manager to ensure we are tracking latest Beliefs on the network
	 * @throws InterruptedException 
	 */
	private void maybePollBelief() throws InterruptedException {
		try {
			// Poll only if no recent consensus updates
			long lastConsensus = server.getPeer().getConsensusState().getTimestamp().longValue();
			if (lastConsensus + pollDelay >= Utils.getCurrentTimestamp()) return;

			ArrayList<Convex> conns = new ArrayList<>(connections.values());
			if (conns.size() == 0) {
				// Nothing to do
				return;
			}
			
			// TODO: probably shouldn't make a new connection?
			// Maybe use Convex instance instead of Connection?
			Convex c = conns.get(random.nextInt(conns.size()));

			if (!c.isConnected()) {
				log.warn("Attempted to poll from closed connection");
				return;
			}

			Convex convex = c;
			
			// use requestStatusSync to auto acquire hash of the status instead of the value
			Result result=convex.requestStatusSync(POLL_TIMEOUT_MILLIS);
			if (result.isError()) {
				log.warn("Failure requesting status during polling: "+result);
				return;		
			}
			
			AMap<Keyword,ACell> status = API.ensureStatusMap(result.getValue());
			if (status==null) {
				log.warn("Dubious status response message: "+result);
				return;
			}
			Hash h=RT.ensureHash(status.get(Keywords.BELIEF));

			Belief sb=(Belief) convex.acquire(h).get(POLL_ACQUIRE_TIMEOUT_MILLIS,TimeUnit.MILLISECONDS);

			server.queueBelief(Message.createBelief(sb));
		} catch (Exception t) {
			if (server.isLive()) {
				log.info("Belief Polling failed: {}",t.getClass().toString()+" : "+t.getMessage());
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
			Convex conn=getConnection(p);
			if (conn==null) {
				// Must have lost this connection
				currentPeerCount--;
				continue;
			}

			/*
			 *  Always remove Peers not staked in consensus. This should eliminate Peers that have
			 *  withdrawn, have trivial stake or are slashed from current consideration.
			 */
			PeerStatus ps=s.getPeer(p);
			if ((ps==null)||(ps.getBalance()<=CPoSConstants.MINIMUM_EFFECTIVE_STAKE)) {
				closeConnection(p,"Insufficient stake");
				currentPeerCount--;
				continue;
			}

			/* Drop Peers randomly if they have a small stake
			 * This ensure that new peers will get picked up occasionally and
			 * the distribution of peers tends towards the level of stake over time
			 */
			if ((millisSinceLastUpdate>0)&&(currentPeerCount>=targetPeerCount)) {
				double prop=ps.getTotalStakeShares()/totalStake; // proportion of stake represented by this Peer
				// Very low chance of dropping a Peer with high stake (more than
				double keepChance=Math.min(1.0, prop*targetPeerCount);

				if (keepChance<1.0) {

					double dropRate=millisSinceLastUpdate/(double)Config.PEER_CONNECTION_DROP_TIME;
					if (random.nextDouble()<(dropRate*(1.0-keepChance))) {
						closeConnection(p,"Dropping minor peers");
						currentPeerCount--;
						continue;
					}
				}
			}

		}

		// refresh peers list
		currentPeerCount=connections.size();
		if (currentPeerCount<targetPeerCount) {
			tryRandomConnect(s);
		}
		
		lastConnectionUpdate=Utils.getCurrentTimestamp();
	}

	private void tryRandomConnect(State s) {
		// Connect to a random peer with host address by stake
		// SECURITY: stake weighted connection is important to avoid bad / irrelevant peers
		// influencing the connection pool

		Set<AArrayBlob> potentialPeers=s.getPeers().keySet();
		InetSocketAddress target=null;
		double accStake=0.0;
		for (ACell c:potentialPeers) {
			AccountKey peerKey=RT.ensureAccountKey(c);
			if (connections.containsKey(peerKey)) continue; // skip if already connected

			if (server.getPeerKey().equals(peerKey)) continue; // don't connect to self!!

			PeerStatus ps=s.getPeers().get(peerKey);
			if (ps==null) continue; // skip
			AString hostName=ps.getHostname();
			if (hostName==null) continue; // don't now where to connect to!
			InetSocketAddress maybeAddress=IPUtils.toInetSocketAddress(hostName.toString());
			if (maybeAddress==null) continue;
			
			long peerStake=ps.getPeerStake();
			if (peerStake>CPoSConstants.MINIMUM_EFFECTIVE_STAKE) {
				double t=random.nextDouble()*(accStake+peerStake);
				if (t>=accStake) {
					target=maybeAddress;
				}
				accStake+=peerStake;
			}
		}

		if (target!=null) {
			// Try to connect to Peer. If it fails, no worry, will retry another peer next time
			InetSocketAddress connectTarget=target;
			connectToPeer(target).exceptionally(e -> {
				log.debug("Failed to connect to Peer at "+connectTarget+": "+e.getMessage());
				return null;
			});
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
	public void closeConnection(AccountKey peerKey,String reason) {
		Convex conn=connections.get(peerKey);
		if (conn!=null) {
			log.info("Removed peer connection to "+peerKey+ " Reason="+reason);	
			conn.close();
			connections.remove(peerKey);
		}
	}

	/**
	 * Close all outgoing connections from this Peer
	 */
	public void closeAllConnections() {
		HashMap<AccountKey, Convex> conns=new HashMap<>(getConnections());
		for (AccountKey peerKey:conns.keySet()) {
			closeConnection(peerKey,"Closing all connections");
		}
		connections.clear();
	}

	/**
	 * Gets the current set of outbound peer connections from this server
	 *
	 * @return Set of connections
	 */
	public HashMap<AccountKey,Convex> getConnections() {
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
	public Convex getConnection(AccountKey peerKey) {
		Convex c=connections.get(peerKey);
		if (c==null) return null;
		
		if (!c.isConnected()) {
			closeConnection(peerKey,"Removing already closed connection");
			return null;
		}
		
		return c;
	}

	/**
	 * Returns the number of active connections
	 * @return Number of connections
	 */
	public int getConnectionCount() {
		return connections.size();
	}

	/**
	 * Processes an incoming CHALLENGE message. Validates contextID against
	 * this peer's network ID if present.
	 */
	public void processChallenge(Message m, Peer thisPeer) {
		Hash networkID = thisPeer.getNetworkID();
		m.respondToChallenge(thisPeer.getKeyPair(), ctx -> {
			Hash h = RT.ensureHash(ctx);
			return (h == null) || h.equals(networkID);
		});
	}

	/**
	 * Broadcasts a Message to all connected Peers
	 *
	 * @param msg Message to broadcast
	 *
	 */
	public void broadcast(Message msg) {
		HashMap<AccountKey,Convex> hm=getCurrentConnections();
		
		if (hm.isEmpty()) {
			log.debug("No connections to broadcast to from "+server.getPeerKey());
			return;
		}
		
		ArrayList<Map.Entry<AccountKey,Convex>> left=new ArrayList<>(hm.entrySet());
			
		// Shuffle order for sending
		Utils.shuffle(left);
			
		for (Map.Entry<AccountKey,Convex> me: left) {
			Convex pc=me.getValue();
			CompletableFuture<Result> sent=pc.message(msg);
			if (sent.isDone()) {
				Result r=sent.join();
				if (r.isError()) {
					// log.warn("Immediate error broadcasting: "+r);
				}
			}
		}
		
// If we couldn't broadcast to everyone, we are probably overloaded. drop a random connection
//		if ((!hm.isEmpty())&&server.isLive()) {
//			ArrayList<Map.Entry<AccountKey,Convex>> left=new ArrayList<>(hm.entrySet());
//			Map.Entry<AccountKey,Convex> drop=left.get(random.nextInt(left.size()));
//			AccountKey dropKey=drop.getKey();
//			closeConnection(dropKey);
//			log.warn("Unable to send broadcast to "+hm.size()+" peers, dropped one connection to: "+dropKey+ "remaining = "+getConnectionCount());
//		}
	}

	/**
	 * Gets a new hashmap of connection which are currently live
	 * @return
	 */
	private synchronized HashMap<AccountKey, Convex> getCurrentConnections() {
		HashMap<AccountKey, Convex> liveConnections=new HashMap<>();
		for (Map.Entry<AccountKey,Convex> me: connections.entrySet()) {
			AccountKey peerKey=me.getKey();
			Convex c=me.getValue();
			if ((c!=null)&&(c.isConnected())) {
				liveConnections.put(peerKey,c);
			} 
		}
		return liveConnections;
	}

	/**
	 * Connects explicitly to a Peer at the given host address. Attempts
	 * challenge/response verification; falls back to status-based (untrusted)
	 * identification if verification is not supported.
	 *
	 * @param hostAddress Address to connect to
	 * @return Future completing with the Convex connection, or exceptionally on failure
	 */
	public CompletableFuture<Convex> connectToPeer(InetSocketAddress hostAddress) {
		CompletableFuture<Convex> result = new CompletableFuture<>();

		try {
			Convex convex=Convex.connect(hostAddress);
			convex.setStore(server.getStore());
			convex.setKeyPair(server.getKeyPair());

			// Try to identify peer: verify first, fall back to status
			identifyPeer(convex).whenComplete((peerKey, ex) -> {
				if (peerKey==null || ex!=null) {
					convex.close();
					result.completeExceptionally(ex!=null ? ex
						: new IOException("Unable to identify peer at "+hostAddress));
					return;
				}

				Convex existing=getConnection(peerKey);
				if ((existing!=null)&&existing.isConnected()) {
					convex.close();
					result.complete(existing);
				} else {
					addConnection(peerKey, convex);
					result.complete(convex);
				}
			});
		} catch (Exception e) {
			result.completeExceptionally(e);
		}
		return result;
	}

	/**
	 * Identifies a remote peer, first attempting challenge/response verification
	 * (trusted), then falling back to status request (untrusted).
	 *
	 * @param convex Connection to the remote peer
	 * @return Future completing with the peer's AccountKey
	 */
	private CompletableFuture<AccountKey> identifyPeer(Convex convex) {
		Peer peer=server.getPeer();
		Hash networkID=peer.getNetworkID();

		// Try verification with network ID as context
		return convex.verifyPeer(null, networkID).thenCompose(verified -> {
			if (verified!=null) {
				log.info("Verified peer: "+verified+" at "+convex.getHostAddress());
				return CompletableFuture.completedFuture(verified);
			}

			// Verification returned null — fall back to status
			return fallbackIdentify(convex);
		}).exceptionallyCompose(ex -> {
			// Verification failed (timeout, unsupported, etc.) — fall back to status
			log.debug("Peer verification not available at "+convex.getHostAddress()+": "+ex.getMessage());
			return fallbackIdentify(convex);
		});
	}

	/**
	 * Identifies a peer via status request (untrusted fallback).
	 */
	private CompletableFuture<AccountKey> fallbackIdentify(Convex convex) {
		return convex.requestStatus().thenApply(result -> {
			if (result.isError()) {
				log.info("Bad status from remote peer: "+result);
				return null;
			}
			AMap<Keyword,ACell> status=API.ensureStatusMap(result.getValue());
			if (status==null) return null;
			AccountKey peerKey=RT.ensureAccountKey(status.get(Keywords.PEER));
			if (peerKey!=null) {
				log.info("Identified peer via status (unverified): "+peerKey+" at "+convex.getHostAddress());
			}
			return peerKey;
		});
	}



	public synchronized void addConnection(AccountKey peerKey, Convex convex) {
		synchronized(connections) {
			log.debug("Connected to Peer: "+peerKey+ " at "+convex.getHostAddress());
			connections.put(peerKey, convex);
		}
	}

	@Override
	public void close() {
		// broadcast GOODBYE message to all outgoing remote peers
		try {
			Message msg = Message.createGoodBye();
			broadcast(msg);
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
		maybePollBelief();
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
		try {
			Convex conn=getConnection(peerKey);
			if (conn==null) return; // No outbound connection to this peer, so just ignore
			
			if (log.isDebugEnabled()) {
				String message= "Missing data alert "+e.getMissingHash();
				// String s=PrintUtils.printRefTree(m.getPayload().getRef());
				// System.out.println(s);
				log.info(message);
			}
			
			// TODO: possibly fire off request to specific Peer? Unclear if this improves things generally, but might avoid polling
		} catch (Exception ex) {
			log.warn("Unexpected error responding to missing data",ex);
		}
	}

}
