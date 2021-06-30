package convex.peer;

import java.io.IOException;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import convex.core.Peer;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.lang.RT;
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

	private final Server server;
	private final HashMap<AccountKey,Connection> connections = new HashMap<>();

	/**
	 * The list of outgoing challenges that are being made to remote peers
	 */
	private HashMap<AccountKey, ChallengeRequest> challengeList = new HashMap<>();


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


	void processResponse(Message m, Peer thisPeer) {
		try {
			SignedData<ACell> signedData = m.getPayload();

			@SuppressWarnings("unchecked")
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
			if ( networkId == null || !networkId.equals(thisPeer.getNetworkID())) {
				log.log(LEVEL_CHALLENGE_RESPONSE, "response data has incorrect networkId");
				return;
			}
			// check to see if the challenge is for this peer
			AccountKey toPeer = RT.ensureAccountKey(responseValues.get(2));
			if ( toPeer == null || !toPeer.equals(thisPeer.getPeerKey())) {
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
				if ( !challengeHash.equals(challengeSourceHash)) {
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
			server.raiseServerChange("new trusted connection");

		} catch (Throwable t) {
			log.warning("Response Error: " + t);
		}
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
	 * @param isTrusted Only broadcast to trusted peers
	 *
	 */
	public synchronized void broadcast(Message msg, boolean isTrusted) {
		for (Connection pc : connections.values()) {
			try {
				if ( (isTrusted && pc.isTrusted()) || !isTrusted) {
					pc.sendMessage(msg);
				}
			} catch (IOException e) {
				log.warning("Error in broadcast: " + e.getMessage());
			}
		}
	}

}
