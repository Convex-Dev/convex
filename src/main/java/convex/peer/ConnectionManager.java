package convex.peer;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
	private final HashSet<Connection> connections = new HashSet<>();

	public ConnectionManager(Map<Keyword, Object> config) {
		this.config = config;
	}

	public Map<Keyword, Object> getConfig() {
		return config;
	}

	public void addConnection(Connection peerConnection) {
		connections.add(peerConnection);
	}

	public void removeConnection(Connection pc) {
		connections.remove(pc);
	}

	/**
	 * Gets the current set of outbound peer connections from this server
	 * 
	 * @return Set of connections
	 */
	public Set<Connection> getConnections() {
		return connections;
	}

	public void broadcast(Message msg) {
		for (Connection pc : connections) {
			try {
				pc.sendMessage(msg);
			} catch (IOException e) {
				log.warning("Error in broadcast: " + e.getMessage());
			}
		}
	}

}
