package convex.peer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import convex.core.data.AccountKey;
import convex.core.data.Keyword;
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

	private Map<Keyword, Object> config;
	private final HashMap<AccountKey,Connection> connections = new HashMap<>();

	public ConnectionManager(Map<Keyword, Object> config) {
		this.config = config;
	}

	public Map<Keyword, Object> getConfig() {
		return config;
	}

	public void setConnection(AccountKey peerKey, Connection peerConnection) {
		if (connections.containsKey(peerKey)) {
			connections.get(peerKey).close();
			connections.replace(peerKey, peerConnection);
		}
		else {
			connections.put(peerKey, peerConnection);
		}
	}

	public void removeConnection(AccountKey peerKey) {
		connections.remove(peerKey);
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

	/**
	 *
	 * @param msg Message to broadcast
	 *
	 * @param isTrusted Only broadcast to trusted peers
	 *
	 */
	public void broadcast(Message msg, boolean isTrusted) {
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
