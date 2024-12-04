package convex.gui.server;

import java.awt.Dimension;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.gui.components.AbstractGUI;
import convex.gui.peer.PeerComponent;
import convex.gui.repl.REPLPanel;
import convex.peer.Server;
import net.miginfocom.swing.MigLayout;

/**
 * GUI representing a view of a single local peer Server
 */
@SuppressWarnings("serial")
public class PeerWindow extends AbstractGUI {
	ConvexLocal peer;

	public Convex getPeerView() {
		return peer;
	}

	private static final Logger log = LoggerFactory.getLogger(PeerWindow.class.getName());

	JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

	public PeerWindow(ConvexLocal peer) {
		super("Peer Control Panel - " + peer.toString());
		this.peer = peer;
		this.setPreferredSize(new Dimension(1200,1000));

		setLayout(new MigLayout());
		add(tabbedPane, "dock center");
		
		Server server=peer.getLocalServer();
		if (server!=null) {
			try {
				// Convex convex = Convex.connect(server.getHostAddress(), server.getPeerController(),server.getKeyPair());
				tabbedPane.addTab("REPL", null, new REPLPanel(peer), null);
			} catch (Exception t) {
				String msg=("Failed to connect to Peer: "+t);
				t.printStackTrace();
				log.warn(msg);
				tabbedPane.addTab("REPL Error", null, new JLabel(msg), null);
				throw t;
			}
			tabbedPane.addTab("Observation", null, new JScrollPane(new ObserverPanel(server)), null);
		}
		tabbedPane.addTab("Stress", null, new StressPanel(peer), null);
		tabbedPane.addTab("Info", null, new PeerInfoPanel(peer), null);

		PeerComponent pcom = new PeerComponent(peer);
		add(pcom, "dock north");
	}

	@Override
	public void setupFrame(JFrame frame) {
		frame.getContentPane().setLayout(new MigLayout());
		frame.getContentPane().add(this,"dock center");
	}
}
