package convex.gui.components;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;

import javax.swing.JPanel;

import convex.core.Block;
import convex.core.Order;
import convex.core.Peer;
import convex.core.State;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.gui.components.models.StateModel;
import convex.gui.manager.PeerManager;

/**
 * Panel presenting a summary graphic of the most recent blocks for a given
 * PeerView
 */
@SuppressWarnings("serial")
public class BlockViewComponent extends JPanel {

	private PeerView peerView;

	public BlockViewComponent(PeerView peer) {
		this.peerView = peer;

		setBackground(null);
		setPreferredSize(new Dimension(1000, 10));

		if (peer!=null) {
			StateModel<State> model=peer.getStateModel();
			model.addPropertyChangeListener(e -> {
				repaint();
			});
		}
	}

	@Override
	public void paintComponent(Graphics g) {
		g.setColor(Color.black);
		int pw = getWidth();
		int ph = getHeight();
		g.fillRect(0, 0, pw, ph);
		
		Peer p = peerView.peerServer.getPeer();
		Order order = p.getPeerOrder();
		if (order==null) return; // no current peer order - maybe not a valid peer?
		AVector<Block> blocks = order.getBlocks();
		int n = (int) blocks.count();


		int W = 10;
		long tw = W * PeerManager.maxBlock;
		long offset = Math.max(0, tw - pw);

		for (int i = (int) (offset / W); i < n; i++) {
			Color c = Color.orange;
			if (i < order.getProposalPoint()) c = Color.yellow;
			if (i < order.getConsensusPoint()) c = Color.green;
			if (p.getConsensusPoint() != order.getConsensusPoint()) {
				System.out.println("Strange consensus?");
			}
			int x = (int) (W * i - offset);
			g.setColor(c);
			g.fillRect(x + 1, 1, W - 2, W - 2);

			if (c == Color.green) {
				g.setColor(Color.black);
				State s = p.getStates().get(i + 1);
				for (int j = 0; j < 6; j++) {
					Hash h = s.getHash();
					if (h.byteAt(j) < 0) {
						g.fillRect(x + 2, 2 + j, 6, 1);
					}
				}
			}
		}
	}
}
