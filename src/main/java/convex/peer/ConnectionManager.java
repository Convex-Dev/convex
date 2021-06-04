package convex.peer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import convex.core.data.Keyword;
import convex.net.Message;
import convex.net.Connection;

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
	private final HashMap<String,Connection> connections = new HashMap<>();

	public ConnectionManager(Map<Keyword, Object> config) {
		this.config = config;
	}

	public Map<Keyword, Object> getConfig() {
		return config;
	}

	public void setConnection(String hostname, Connection peerConnection) {
		connections.putIfAbsent(hostname, peerConnection);
	}

	public void removeConnection(String hostname) {
		connections.remove(hostname);
	}

	/**
	 * Gets the current set of outbound peer connections from this server
	 *
	 * @return Set of connections
	 */
	public HashMap<String,Connection> getConnections() {
		return connections;
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
				if ( isTrusted && pc.isTrusted() | !isTrusted) {
					pc.sendMessage(msg);
				}
			} catch (IOException e) {
				log.warning("Error in broadcast: " + e.getMessage());
			}
		}
	}

}
