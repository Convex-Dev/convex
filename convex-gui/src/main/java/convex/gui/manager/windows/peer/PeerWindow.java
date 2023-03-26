package convex.gui.manager.windows.peer;

import java.awt.BorderLayout;

import javax.swing.JTabbedPane;

import convex.gui.components.PeerComponent;
import convex.gui.components.PeerView;
import convex.gui.manager.PeerGUI;
import convex.gui.manager.windows.BaseWindow;

@SuppressWarnings("serial")
public class PeerWindow extends BaseWindow {
	PeerView peer;

	public PeerView getPeerView() {
		return peer;
	}

	JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

	public PeerWindow(PeerGUI manager, PeerView peer) {
		super(manager);
		this.peer = peer;

		add(tabbedPane, BorderLayout.CENTER);
		tabbedPane.addTab("REPL", null, new REPLPanel(this.getPeerView()), null);
		tabbedPane.addTab("Stress", null, new StressPanel(this.getPeerView()), null);
		tabbedPane.addTab("Info", null, new PeerInfoPanel(this.getPeerView()), null);

		PeerComponent pcom = new PeerComponent(manager, peer);
		add(pcom, BorderLayout.NORTH);

	}

	@Override
	public String getTitle() {
		try {
			return "Peer view - " + peer.getHostAddress();
		} catch (Exception e) {
			return "Peer view - Unknown";
		}
	}

}
