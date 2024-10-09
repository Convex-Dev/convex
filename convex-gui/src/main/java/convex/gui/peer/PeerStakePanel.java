package convex.gui.peer;

import javax.swing.JPanel;

import convex.api.Convex;

@SuppressWarnings("serial")
public class PeerStakePanel extends JPanel {

	protected Convex convex;

	public PeerStakePanel(Convex convex) {
		this.convex=convex;
	}

}
