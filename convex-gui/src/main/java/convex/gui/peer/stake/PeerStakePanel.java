package convex.gui.peer.stake;

import java.awt.BorderLayout;

import javax.swing.JPanel;
import javax.swing.JScrollPane;

import convex.api.Convex;

@SuppressWarnings("serial")
public class PeerStakePanel extends JPanel {

	protected Convex convex;

	public PeerStakePanel(Convex convex) {
		super (new BorderLayout());
		this.convex=convex;
		
		PeerStakeTable peerTable=new PeerStakeTable(convex); 
		add(new JScrollPane(peerTable),BorderLayout.CENTER);
		
	}

}
