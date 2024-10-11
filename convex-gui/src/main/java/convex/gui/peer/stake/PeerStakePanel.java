package convex.gui.peer.stake;

import java.awt.BorderLayout;

import javax.swing.JPanel;
import javax.swing.JScrollPane;

import convex.api.Convex;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;

@SuppressWarnings("serial")
public class PeerStakePanel extends JPanel {

	protected Convex convex;

	public PeerStakePanel(Convex convex) {
		super (new BorderLayout());
		this.convex=convex;
		
		PeerStakeTable peerTable=new PeerStakeTable(convex); 
		add(new JScrollPane(peerTable),BorderLayout.CENTER);
		
		ActionPanel ap=new ActionPanel();
		add(ap,BorderLayout.SOUTH);
		
		ap.add(new ActionButton("Refresh", 0xe5d5, e->peerTable.refresh()) );
		ap.add(new ActionButton("Set Stake...", 0xf5dc, e->peerTable.refresh()) );
	}

}
