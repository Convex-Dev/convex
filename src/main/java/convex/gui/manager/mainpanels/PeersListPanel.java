package convex.gui.manager.mainpanels;

import java.awt.BorderLayout;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import convex.api.Convex;
import convex.core.Init;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.store.Stores;
import convex.gui.components.ActionPanel;
import convex.gui.components.PeerComponent;
import convex.gui.components.PeerView;
import convex.gui.components.ScrollyList;
import convex.gui.manager.PeerManager;
import convex.peer.API;
import convex.peer.Server;
import etch.EtchStore;

@SuppressWarnings({ "serial", "unused" })
public class PeersListPanel extends JPanel {

	JPanel peersPanel;
	static DefaultListModel<PeerView> peerList = new DefaultListModel<PeerView>();

	JPanel peerViewPanel;
	JScrollPane scrollPane;

	private static final Logger log = Logger.getLogger(PeersListPanel.class.getName());

	public void launchAllPeers(PeerManager manager) {
		List<Server> serverList = API.launchLocalPeers(Init.NUM_PEERS, Init.KEYPAIRS, Init.FIRST_PEER, null);
		for (Server server: serverList) {
			PeerView peer = new PeerView();
			peer.peerServer = server;
			// InetSocketAddress sa = server.getHostAddress();
			addPeer(peer);
		}
	}

	public static PeerView getFirst() {
		return peerList.elementAt(0);
	}

	/**
	 * Gets a list of all locally operating Servers from the current peer list.
	 *
	 * @return List of local PeerView objects
	 */
	public List<PeerView> getPeerViews() {
		ArrayList<PeerView> al = new ArrayList<>();
		int n = peerList.getSize();
		for (int i = 0; i < n; i++) {
			PeerView p = peerList.getElementAt(i);
			al.add(p);
		}
		return al;
	}

	private void addPeer(PeerView peer) {
		peerList.addElement(peer);
	}

	/**
	 * Create the panel.
	 */
	public PeersListPanel(PeerManager manager) {
		setLayout(new BorderLayout(0, 0));

		JPanel toolBar = new ActionPanel();
		add(toolBar, BorderLayout.SOUTH);

		// JButton btnLaunch = new JButton("Launch!");
		// toolBar.add(btnLaunch);
		// btnLaunch.addActionListener(e -> launchPeer(manager));

		JButton btnConnect = new JButton("Connect...");
		toolBar.add(btnConnect);
		btnConnect.addActionListener(e -> {
			String input = JOptionPane.showInputDialog("Enter host address: ", "");
			String[] ss = input.split(":");
			String host = ss[0].trim();
			int port = (ss.length > 1) ? Integer.parseInt(ss[1].trim()) : 0;
			InetSocketAddress hostAddress = new InetSocketAddress(host, port);
			Convex pc;
			try {
				// TODO: we want to receive anything?
				pc = Convex.connect(hostAddress, Init.HERO,null);
				PeerView pv = new PeerView();
				pv.peerConnection = pc;
				addPeer(pv);
			} catch (Throwable e1) {
				JOptionPane.showMessageDialog(this, "Connect failed: " + e1.toString());
			}

		});

		ScrollyList<PeerView> scrollyList = new ScrollyList<PeerView>(peerList,
				peer -> new PeerComponent(manager, peer));
		add(scrollyList, BorderLayout.CENTER);
	}

	public void closePeers() {
		int n = peerList.getSize();
		for (int i = 0; i < n; i++) {
			PeerView p = peerList.getElementAt(i);
			p.peerServer.close();
		}
	}

}
