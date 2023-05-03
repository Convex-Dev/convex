package convex.gui.manager.windows.peer;

import java.awt.BorderLayout;

import javax.swing.JTabbedPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.gui.components.PeerComponent;
import convex.gui.components.PeerView;
import convex.gui.manager.PeerGUI;
import convex.gui.manager.windows.BaseWindow;
import convex.peer.Server;

@SuppressWarnings("serial")
public class PeerWindow extends BaseWindow {
	PeerView peer;

	public PeerView getPeerView() {
		return peer;
	}

	private static final Logger log = LoggerFactory.getLogger(PeerWindow.class.getName());

	JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

	public PeerWindow(PeerGUI manager, PeerView peer) {
		super(manager);
		this.peer = peer;

		add(tabbedPane, BorderLayout.CENTER);
		
		Server server=peer.peerServer;
		if (server!=null) {
			try {
			
			Convex convex = Convex.connect(server.getHostAddress(), server.getPeerController(),server.getKeyPair());
		
			tabbedPane.addTab("REPL", null, new REPLPanel(convex), null);
			} catch (Throwable t) {
				log.warn("Unable to create Peer Controller REPL");
			}
		}
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
