package convex.gui.components;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

import convex.gui.manager.PeerManager;
import convex.gui.manager.Toolkit;
import convex.gui.manager.windows.etch.EtchWindow;
import convex.gui.manager.windows.peer.PeerWindow;
import convex.gui.manager.windows.state.StateWindow;
import convex.peer.Server;
import etch.store.EtchStore;

@SuppressWarnings("serial")
public class PeerComponent extends BaseListComponent {

	public PeerView peer;
	JTextArea description;
	private PeerManager manager;

	public void launchPeerWindow(PeerView peer) {
		PeerWindow pw = new PeerWindow(manager, peer);
		pw.launch();
	}

	public void launchEtchWindow(PeerView peer) {
		EtchWindow ew = new EtchWindow(manager, peer);
		ew.launch();
	}

	public void launchExploreWindow(PeerView peer) {
		Server s = peer.peerServer;
		Object p = s.getPeer().getConsensusState();
		StateWindow pw = new StateWindow(manager, p);
		pw.launch();
	}

	public PeerComponent(PeerManager manager, PeerView value) {
		this.manager = manager;
		this.peer = value;

		setLayout(new BorderLayout(0, 0));

		// setPreferredSize(new Dimension(1000, 90));

		JButton button = new JButton("");
		button.setBorder(null);
		add(button, BorderLayout.WEST);
		button.setIcon(Toolkit.CAKE);
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
		
		if (peer!=null) {
			PeerManager.getStateModel().addPropertyChangeListener(e->{
				description.setText(peer.toString());
			});
		}

		// Setup popup menu for peer
		JPopupMenu popupMenu = new JPopupMenu();
		if (peer.isLocal()) {
			JMenuItem closeButton = new JMenuItem("Shutdown Peer");
			closeButton.addActionListener(e -> {
				peer.close();
			});
			popupMenu.add(closeButton);

			JMenuItem exploreButton = new JMenuItem("Explore state");
			exploreButton.addActionListener(e -> {
				launchExploreWindow(peer);
			});
			popupMenu.add(exploreButton);

			if (peer.peerServer.getStore() instanceof EtchStore) {
				JMenuItem storeButton = new JMenuItem("Explore Etch store");
				storeButton.addActionListener(e -> {
					launchEtchWindow(peer);
				});
				popupMenu.add(storeButton);
			}
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

		JPanel panel_1 = new BlockViewComponent(peer);
		add(panel_1, BorderLayout.SOUTH);

		DropdownMenu dm = new DropdownMenu(popupMenu);
		add(dm, BorderLayout.EAST);

	}
}
