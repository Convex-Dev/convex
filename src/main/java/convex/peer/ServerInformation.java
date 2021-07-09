package convex.peer;


import convex.core.Order;
import convex.core.Peer;
import convex.core.data.AccountKey;
import convex.core.data.Hash;

/**
 * Utility class to store extract server information samples
 */
public class ServerInformation {

	private AccountKey peerKey;
	private String hostname;
	private int connectionCount;
	private int trustedConnectionCount;
	private boolean isSynced;
	private boolean isJoined;
	private Hash networkID;
	private long consensusPoint;
	private Hash stateHash;
    private Hash beliefHash;
	private long blockCount;


	private ServerInformation(Server server,  ConnectionManager manager) {
		load(server, manager);
	}

	public static ServerInformation create(Server server) {
		return new ServerInformation(server, server.getConnectionManager());
	}

	protected void load(Server server,  ConnectionManager manager) {
		Peer peer = server.getPeer();
		Order order = peer.getPeerOrder();

		peerKey = peer.getPeerKey();
		hostname = server.getHostname();
		connectionCount = manager.getConnectionCount();
		trustedConnectionCount = manager.getTrustedConnectionCount();
		isSynced =  order != null && peer.getConsensusPoint() > 0;
		networkID = peer.getNetworkID();
		consensusPoint = peer.getConsensusPoint();
        isJoined = connectionCount > 1;
		stateHash = peer.getConsensusState().getHash();
        beliefHash = peer.getBelief().getHash();
		blockCount = 0;
		if (order != null ) {
			blockCount = order.getBlockCount();
		}
	}

	public AccountKey getPeerKey() {
		return peerKey;
	}
	public String getHostname() {
		return hostname;
	}
	public int getConnectionCount() {
		return connectionCount;
	}
	public int getTrustedConnectionCount() {
		return trustedConnectionCount;
	}
	public boolean isSynced() {
		return isSynced;
	}
	public boolean isJoined() {
		return isJoined;
	}
	public Hash getNetworkID() {
		return networkID;
	}
	public long getConsensusPoint() {
		return consensusPoint;
	}
	public Hash getStateHash() {
		return stateHash;
	}
	public Hash getBeliefHash() {
		return beliefHash;
	}
	public long getBlockCount() {
		return blockCount;
	}
}
