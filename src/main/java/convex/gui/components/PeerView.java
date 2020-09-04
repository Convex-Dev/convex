package convex.gui.components;

import java.net.InetSocketAddress;

import convex.core.Peer;
import convex.core.State;
import convex.core.data.Address;
import convex.core.data.PeerStatus;
import convex.core.util.Text;
import convex.gui.components.models.StateModel;
import convex.gui.manager.PeerManager;
import convex.net.Connection;
import convex.peer.Server;

public class PeerView {
	public Connection peerConnection = null;
	public Server peerServer = null;

	public StateModel<Peer> state = new StateModel<>(null);

	@Override
	public String toString() {
		StringBuilder sb=new StringBuilder();
		if (peerServer != null) {
			State state=PeerManager.getLatestState();
			Address paddr=peerServer.getAddress();
			sb.append("0x"+paddr.toChecksumHex()+"\n");
			sb.append("Local peer on: " + peerServer.getHostAddress() + " with store "+peerServer.getStore()+"\n");
			
			PeerStatus ps=state.getPeer(paddr);
			if (ps!=null) {
				sb.append("Peer Stake:  "+Text.toFriendlyBalance(ps.getOwnStake()));
				sb.append("    ");
				sb.append("Delegated Stake:  "+Text.toFriendlyBalance(ps.getDelegatedStake()));
			}
		} else if (peerConnection != null) {
			sb.append("Remote peer at: " + peerConnection.getRemoteAddress()+"\n");
		} else {
			sb.append("Unknown");
		}
		return sb.toString();
	}

	/**
	 * Poll the current peer state. Returns null if not a local peer.
	 * 
	 * @return Peer state for this PeerView
	 */
	public Peer checkPeer() {
		if (peerServer != null) {
			Peer p = peerServer.getPeer();
			state.setValue(p);
			return p;
		}
		return null;
	}

	public void close() {
		if (peerServer != null) peerServer.close();
		if (peerConnection != null) peerConnection.close();
	}

	public InetSocketAddress getHostAddress() {
		// this is direct connection to a peer, so get its host address
		if (peerServer != null) return peerServer.getHostAddress();

		// need to get the remote address from the PeerConnection
		return (InetSocketAddress) peerConnection.getRemoteAddress();
	}

	public boolean isLocal() {
		return peerServer != null;
	}

}