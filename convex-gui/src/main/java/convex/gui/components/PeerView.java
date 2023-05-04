package convex.gui.components;

import java.net.InetSocketAddress;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Peer;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Address;
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
	public Convex convex = null;
	public Server server = null;

	public StateModel<Peer> peerModel = new StateModel<>(null);
	public StateModel<State> stateModel = new StateModel<>(null);

	public PeerView(Server server) {
		this.server=server;
	}

	public PeerView(Convex pc) {
		convex=pc;
	}

	@Override
	public String toString() {
		StringBuilder sb=new StringBuilder();
		if (server != null) {
			State state=PeerGUI.getLatestState();
			AccountKey paddr=server.getPeerKey();
			sb.append("0x"+paddr.toChecksumHex()+"\n");
			sb.append("Local peer on: " + server.getHostAddress() + " with store "+server.getStore()+"\n");
			
			PeerStatus ps=state.getPeer(paddr);
			if (ps!=null) {
				sb.append("Peer Stake:  "+Text.toFriendlyNumber(ps.getPeerStake()));
				sb.append("    ");
				sb.append("Delegated Stake:  "+Text.toFriendlyNumber(ps.getDelegatedStake()));
			}
			ConnectionManager cm=server.getConnectionManager();
			sb.append("\n");
			sb.append("Connections: "+cm.getConnectionCount());
		} else if (convex != null) {
			sb.append(convex.toString());
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
		if (server != null) {
			Peer p = server.getPeer();
			peerModel.setValue(p);
			if (p!=null) stateModel.setValue(p.getConsensusState());
			return p;
		}
		return null;
	}

	public void close() {
		if (server != null) server.close();
		if (convex != null) convex.close();
	}

	/**
	 * Gets host Address, or null if not available
	 * @return Host socket address
	 */
	public InetSocketAddress getHostAddress() {
		// this is direct connection to a peer, so get its host address
		if (isLocal()) return server.getHostAddress();

		// need to get the remote address from the PeerConnection
		return ((ConvexRemote) convex).getRemoteAddress();
	}

	public boolean isLocal() {
		return server != null;
	}

	public StateModel<State> getStateModel() {
		return stateModel;
	}

	public Address getAddress() {
		if (convex!=null) return convex.getAddress();
		return server.getPeerController();
	}

	public AKeyPair getKeyPair() {
		if (convex!=null) return convex.getKeyPair();
		return server.getKeyPair();
	}

}