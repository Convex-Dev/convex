package convex.gui.peer.windoes;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.gui.components.PeerComponent;
import convex.gui.peer.PeerGUI;
import convex.gui.peer.windows.BaseWindow;
import convex.peer.Server;

@SuppressWarnings("serial")
public class PeerWindow extends BaseWindow {
	ConvexLocal peer;

	public Convex getPeerView() {
		return peer;
	}

	private static final Logger log = LoggerFactory.getLogger(PeerWindow.class.getName());

	JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

	public PeerWindow(PeerGUI manager, ConvexLocal peer) {
		super(manager);
		this.peer = peer;

		add(tabbedPane, BorderLayout.CENTER);
		this.setPreferredSize(new Dimension(1200,1000));
		
		Server server=peer.getLocalServer();
		if (server!=null) {
			try {
				// Convex convex = Convex.connect(server.getHostAddress(), server.getPeerController(),server.getKeyPair());
				tabbedPane.addTab("REPL", null, new REPLPanel(peer), null);
			} catch (Throwable t) {
				String msg=("Failed to connect to Peer: "+t);
				log.warn(msg);
				tabbedPane.addTab("REPL Error", null, new JLabel(msg), null);
				return;
			}
			tabbedPane.addTab("Observation", null, new JScrollPane(new ObserverPanel(server)), null);
		}
		tabbedPane.addTab("Stress", null, new StressPanel(manager,peer), null);
		tabbedPane.addTab("Info", null, new PeerInfoPanel(peer), null);

		PeerComponent pcom = new PeerComponent(manager, peer);
		add(pcom, BorderLayout.NORTH);
	}

	@Override
	public String getTitle() {
		return "Peer Control - " + peer.toString();
	}

}
