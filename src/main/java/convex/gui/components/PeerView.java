package convex.gui.components;

import java.net.InetSocketAddress;

import convex.api.Convex;
import convex.core.Peer;
import convex.core.State;
import convex.core.data.AccountKey;
import convex.core.data.PeerStatus;
import convex.core.util.Text;
import convex.gui.components.models.StateModel;
import convex.gui.manager.PeerGUI;
import convex.peer.ConnectionManager;
import convex.peer.Server;

/**
 * Class representing a lightweight view of a Peer.
 * 
 * Peer may be either a local Server or remote.
 */
public class PeerView {
	public Convex peerConnection = null;
	public Server peerServer = null;

	public StateModel<Peer> peerModel = new StateModel<>(null);
	public StateModel<State> stateModel = new StateModel<>(null);

	@Override
	public String toString() {
		StringBuilder sb=new StringBuilder();
		if (peerServer != null) {
			State state=PeerGUI.getLatestState();
			AccountKey paddr=peerServer.getPeerKey();
			sb.append("0x"+paddr.toChecksumHex()+"\n");
			sb.append("Local peer on: " + peerServer.getHostAddress() + " with store "+peerServer.getStore()+"\n");
			
			PeerStatus ps=state.getPeer(paddr);
			if (ps!=null) {
				sb.append("Peer Stake:  "+Text.toFriendlyBalance(ps.getPeerStake()));
				sb.append("    ");
				sb.append("Delegated Stake:  "+Text.toFriendlyBalance(ps.getDelegatedStake()));
			}
			ConnectionManager cm=peerServer.getConnectionManager();
			sb.append("\n");
			sb.append("Connections: "+cm.getConnectionCount());
		} else if (peerConnection != null) {
			sb.append("Remote peer at: " + peerConnection.getRemoteAddress()+"\n");
		} else {
			sb.append("Unknown");
		}
		return sb.toString();
	}

	/**
	 * Poll the current peer state. Updates state models if necessary.
	 * 
	 * Returns null if not a local peer.
	 * 
	 * @return Peer state for this PeerView
	 */
	public Peer checkPeer() {
		if (peerServer != null) {
			Peer p = peerServer.getPeer();
			peerModel.setValue(p);
			if (p!=null) stateModel.setValue(p.getConsensusState());
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

	public StateModel<State> getStateModel() {
		return stateModel;
	}

}