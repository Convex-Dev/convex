package convex.gui.components;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;

import javax.swing.JPanel;

import convex.core.Block;
import convex.core.Order;
import convex.core.Peer;
import convex.core.State;
import convex.core.crypto.Hash;
import convex.core.data.AVector;
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
			peer.state.addPropertyChangeListener(e -> {
				repaint();
			});
		}
	}

	@Override
	public void paintComponent(Graphics g) {
		Peer p = peerView.peerServer.getPeer();
		Order chain = p.getPeerOrder();
		AVector<Block> blocks = chain.getBlocks();
		int n = (int) blocks.count();
		g.setColor(Color.black);
		int pw = getWidth();
		int ph = getHeight();
		g.fillRect(0, 0, pw, ph);

		int W = 10;
		long tw = W * PeerManager.maxBlock;
		long offset = Math.max(0, tw - pw);

		for (int i = (int) (offset / W); i < n; i++) {
			Color c = Color.orange;
			if (i < chain.getProposalPoint()) c = Color.yellow;
			if (i < chain.getConsensusPoint()) c = Color.green;
			if (p.getConsensusPoint() != chain.getConsensusPoint()) {
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
					if (h.get(j) < 0) {
						g.fillRect(x + 2, 2 + j, 6, 1);
					}
				}
			}
		}
	}
}
