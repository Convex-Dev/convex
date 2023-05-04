package convex.gui.components;

import java.awt.BorderLayout;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Peer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.gui.client.ConvexClient;
import convex.gui.components.models.StateModel;
import convex.gui.manager.PeerGUI;
import convex.gui.manager.windows.etch.EtchWindow;
import convex.gui.manager.windows.peer.PeerWindow;
import convex.gui.manager.windows.state.StateWindow;
import convex.gui.utils.Toolkit;
import convex.peer.Server;
import etch.EtchStore;

@SuppressWarnings("serial")
public class PeerComponent extends BaseListComponent {

	public PeerView peer;
	JTextArea description;
	private PeerGUI manager;

	public void launchPeerWindow(PeerView peer) {
		try {
			PeerWindow pw = new PeerWindow(manager, peer);
			pw.launch();
		} catch (Exception e) {
			// Ignore
		}
	}

	public void launchEtchWindow(PeerView peer) {
		EtchWindow ew = new EtchWindow(manager, peer);
		ew.launch();
	}

	public void launchExploreWindow(PeerView peer) {
		Server s = peer.server;
		ACell p = s.getPeer().getConsensusState();
		StateWindow pw = new StateWindow(manager, p);
		pw.launch();
	}

	public PeerComponent(PeerGUI manager, PeerView value) {
		this.manager = manager;
		this.peer = value;

		setLayout(new BorderLayout(0, 0));

		// setPreferredSize(new Dimension(1000, 90));

		JButton button = new JButton("");
		button.setBorder(null);
		add(button, BorderLayout.WEST);
		button.setIcon(Toolkit.CONVEX);
		button.addActionListener(e -> {
			launchPeerWindow(this.peer);
		});

		JPanel panel = new JPanel();
		panel.setBorder(new EmptyBorder(5, 5, 5, 5));
		add(panel);
		panel.setLayout(new BorderLayout(0, 0));

		description = new JTextArea((peer == null) ? "No peer" : peer.toString());
		description.setFont(Toolkit.SMALL_MONO_FONT);
		description.setEditable(false);
		description.setBorder(null);
		description.setBackground(null);
		panel.add(description, BorderLayout.CENTER);

		// Setup popup menu for peer
		JPopupMenu popupMenu = new JPopupMenu();
		if (peer.isLocal()) {
			JMenuItem closeButton = new JMenuItem("Shutdown Peer");
			closeButton.addActionListener(e -> {
				try {
					peer.server.shutdown();
				} catch (Exception e1) {
					// ignore
				}
			});
			popupMenu.add(closeButton);

			JMenuItem exploreButton = new JMenuItem("Explore state");
			exploreButton.addActionListener(e -> {
				launchExploreWindow(peer);
			});
			popupMenu.add(exploreButton);

			if (peer.server.getStore() instanceof EtchStore) {
				JMenuItem storeButton = new JMenuItem("Explore Etch store");
				storeButton.addActionListener(e -> {
					launchEtchWindow(peer);
				});
				popupMenu.add(storeButton);
			}
			
			
			JMenuItem killConn = new JMenuItem("Kill Connections");
			killConn.addActionListener(e -> {
				peer.server.getConnectionManager().closeAllConnections();
			});
			popupMenu.add(killConn);
			
		} else {
			JMenuItem closeButton = new JMenuItem("Close connection");
			closeButton.addActionListener(e -> {
				peer.close();
			});
			popupMenu.add(closeButton);
		}

		JMenuItem replButton = new JMenuItem("Launch REPL");
		replButton.addActionListener(e -> launchPeerWindow(this.peer));
		popupMenu.add(replButton);
		
		JMenuItem clientButton = new JMenuItem("Connect Client");
		clientButton.addActionListener(e -> launchClientWindow(this.peer));
		popupMenu.add(clientButton);

		JPanel blockView = new BlockViewComponent(peer);
		add(blockView, BorderLayout.SOUTH);

		DropdownMenu dm = new DropdownMenu(popupMenu);
		add(dm, BorderLayout.EAST);
		
		if (peer!=null) {
			StateModel<Peer> model=peer.peerModel;
			if (model!=null) {
				model.addPropertyChangeListener(e->{
					blockView.repaint();
					description.setText(peer.toString());
				});
			}
		}
		
		PeerGUI.tickState.addPropertyChangeListener(e->{
			description.setText(peer.toString());
		});

	}

	private void launchClientWindow(PeerView peer) {
		try {
			Convex convex = ConvexRemote.connect(peer.getHostAddress());
			Address addr=peer.getAddress();
			AKeyPair kp=peer.getKeyPair();;
			convex.setAddress(addr,kp);
			ConvexClient.launch(convex);
		} catch (IOException | TimeoutException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
}
