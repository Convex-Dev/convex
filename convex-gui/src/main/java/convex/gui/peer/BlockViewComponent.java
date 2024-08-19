package convex.gui.peer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;

import javax.swing.JPanel;

import convex.api.ConvexLocal;
import convex.core.Block;
import convex.core.Constants;
import convex.core.Order;
import convex.core.Peer;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.peer.Server;

/**
 * Panel presenting a summary graphic of the most recent blocks for a given
 * PeerView
 */
@SuppressWarnings("serial")
public class BlockViewComponent extends JPanel {

	private ConvexLocal peerView;

	public BlockViewComponent(ConvexLocal peer) {
		this.peerView = peer;

		setBackground(null);
		setMinimumSize(new Dimension(100, 10));
		setPreferredSize(new Dimension(800, 10));
	}

	@Override
	public void paintComponent(Graphics g) {
		g.setColor(Color.black);
		int pw = getWidth();
		int ph = getHeight();
		g.fillRect(0, 0, pw, ph);
		
		Server server=peerView.getLocalServer();
		if (server==null) return; // not a local server
		if (!server.isLive()) return;
		
		AStore tempStore=Stores.current(); // just in case, since we are reading from a specific peer
		try {
			Stores.setCurrent(server.getStore());
			
			Peer p = server.getPeer();
			Order order = p.getPeerOrder();
			if (order==null) return; // no current peer order - maybe not a valid peer?
			AVector<SignedData<Block>> blocks = order.getBlocks();
			int n = (int) blocks.count();
	
	
			int W = 10;
			long tw = W * PeerGUI.getMaxBlockCount();
			long offset = Math.max(0, tw - pw);
	
			for (int i = (int) (offset / W); i < n; i++) {
				Color c = Color.orange;
				if (i < order.getProposalPoint()) c = Color.yellow;
				if (i < order.getConsensusPoint()) c = Color.green;
				if (p.getFinalityPoint() != order.getConsensusPoint(Constants.CONSENSUS_LEVEL_FINALITY)) {
					System.err.println("BlockViewComponent: Strange consensus?");
				}
				int x = (int) (W * i - offset);
				g.setColor(c);
				g.fillRect(x + 1, 1, W - 2, W - 2);
	
				if (c == Color.green) {
					g.setColor(Color.black);
					SignedData<Block> s = blocks.get(i);
					for (int j = 0; j < 6; j++) {
						Hash h = s.getHash();
						if (h.byteAt(j) < 0) {
							g.fillRect(x + 2, 2 + j, 6, 1);
						}
					}
				}
			}
		} finally {
			Stores.setCurrent(tempStore);
		}
	}
}
