package convex.peer;


import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.Order;
import convex.core.Peer;
import convex.peer.Server;


public class ServerInformation {

	private AccountKey peerKey;
	private String hostname;
	private int connectionCount;
	private int trustedConnectionCount;
	private boolean isSynced;
	private boolean isJoined;
	private Hash networkId;
	private long consensusPoint;
	private Hash stateHash;
	private long blockCount;


	private ServerInformation(Server server,  ConnectionManager manager) {
		load(server, manager);
	}

	public static ServerInformation create(Server server, ConnectionManager manager) {
		return new ServerInformation(server, manager);
	}

	protected void load(Server server,  ConnectionManager manager) {
		Peer peer = server.getPeer();
		Order order = peer.getPeerOrder();

		peerKey = peer.getPeerKey();
		hostname = server.getHostname();
		connectionCount = manager.getConnectionCount();
		trustedConnectionCount = manager.getTrustedConnectionCount();
		isSynced =  order != null;
		networkId = server.getNetworkId();
		isJoined = networkId != null;
		consensusPoint = peer.getConsensusPoint();
		stateHash = peer.getConsensusState().getHash();
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
	public Hash getNetworkId() {
		return networkId;
	}
	public long getConsensusPoint() {
		return consensusPoint;
	}
	public Hash getStateHash() {
		return stateHash;
	}
	public long getBlockCount() {
		return blockCount;
	}
}
