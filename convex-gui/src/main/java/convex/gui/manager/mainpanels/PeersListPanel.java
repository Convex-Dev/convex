package convex.gui.manager.mainpanels;

import java.awt.BorderLayout;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeoutException;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Coin;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.WalletEntry;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.gui.components.ActionPanel;
import convex.gui.components.PeerComponent;
import convex.gui.components.ScrollyList;
import convex.gui.manager.PeerGUI;
import convex.peer.API;
import convex.peer.Server;

@SuppressWarnings({ "serial", "unused" })
public class PeersListPanel extends JPanel {

	JPanel peersPanel;

	JPanel peerViewPanel;
	JScrollPane scrollPane;

	private static final Logger log = LoggerFactory.getLogger(PeersListPanel.class.getName());

	public void launchAllPeers(PeerGUI manager) {
		try {
			int N=PeerGUI.KEYPAIRS.size();
			List<Server> serverList = API.launchLocalPeers(PeerGUI.KEYPAIRS,PeerGUI.genesisState);
			for (Server server: serverList) {
				Convex convex=Convex.connect(server, server.getPeerController(), server.getKeyPair());
				addPeer(convex);
				
				// initial wallet list
		        WalletEntry we = WalletEntry.create(server.getPeerController(), server.getKeyPair());
				WalletPanel.addWalletEntry(we);
			}
		} catch (Exception e) {
			if (e instanceof ClosedChannelException) {
				// Ignore
			} else {
				throw(e);
			}		
		}
	}
	
	public void launchPeer(PeerGUI manager) {
		AKeyPair kp=AKeyPair.generate();
		
		try {
			Server base=getFirst().getLocalServer();
			Convex convex=Convex.connect(base, base.getPeerController(), base.getKeyPair());
			Address a= convex.createAccountSync(kp.getAccountKey());
			convex.transferSync(a, Coin.EMERALD);
			
			WalletPanel.addWalletEntry(WalletEntry.create(a, kp));
			
			// Set up Peer in base server
			convex=Convex.connect(base, a, kp);
			AccountKey key=kp.getAccountKey();
			Result rcr=convex.transactSync("(create-peer "+key+" 10000000000000)");
			if (rcr.isError()) log.warn("Error creating peer: "+rcr);
			
			HashMap<Keyword, Object> config=new HashMap<>();
			config.put(Keywords.KEYPAIR, kp);
			config.put(Keywords.CONTROLLER, a);
			config.put(Keywords.STATE, PeerGUI.genesisState);
			Server server=API.launchPeer(config);
			server.getConnectionManager().connectToPeer(base.getHostAddress());
			server.setHostname("localhost:"+server.getPort());
			base.getConnectionManager().connectToPeer(server.getHostAddress());
			
			convex=Convex.connect(server, a, kp);
			addPeer(convex);
		} catch (TimeoutException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public static Convex getFirst() {
		return PeerGUI.peerList.elementAt(0);
	}

	/**
	 * Gets a list of all locally operating Servers from the current peer list.
	 *
	 * @return List of local PeerView objects
	 */
	public List<Convex> getPeerViews() {
		ArrayList<Convex> al = new ArrayList<>();
		int n = PeerGUI.peerList.getSize();
		for (int i = 0; i < n; i++) {
			Convex p = PeerGUI.peerList.getElementAt(i);
			al.add(p);
		}
		return al;
	}

	private void addPeer(Convex convex) {
		PeerGUI.peerList.addElement(convex);
	}

	/**
	 * Create the panel.
	 * @param manager PeerGUI instance
	 */
	public PeersListPanel(PeerGUI manager) {
		setLayout(new BorderLayout(0, 0));

		JPanel toolBar = new ActionPanel();
		add(toolBar, BorderLayout.SOUTH);

		JButton btnLaunch = new JButton("Launch!");
		toolBar.add(btnLaunch);
		btnLaunch.addActionListener(e -> launchPeer(manager));

		JButton btnConnect = new JButton("Connect...");
		toolBar.add(btnConnect);
		btnConnect.addActionListener(e -> {
			String input = JOptionPane.showInputDialog("Enter host address: ", "localhost:18888");
			if (input==null) return; // no result?

			String[] ss = input.split(":");
			String host = ss[0].trim();
			int port = (ss.length > 1) ? Integer.parseInt(ss[1].trim()) : 0;
			InetSocketAddress hostAddress = new InetSocketAddress(host, port);
			ConvexRemote pc;
			try {
				// TODO: we want to receive anything?
				pc = Convex.connect(hostAddress, null,null);
				addPeer(pc);
			} catch (Throwable e1) {
				JOptionPane.showMessageDialog(this, "Connect failed: " + e1.toString());
			}

		});

		ScrollyList<Convex> scrollyList = new ScrollyList<Convex>(PeerGUI.peerList,
				peer -> new PeerComponent(manager, peer));
		add(scrollyList, BorderLayout.CENTER);
	}

	public void closePeers() {
		int n = PeerGUI.peerList.getSize();
		for (int i = 0; i < n; i++) {
			Convex p = PeerGUI.peerList.getElementAt(i);
			try {
				p.getLocalServer().close();
			} catch (Exception e) {
				// ignore
			}
		}
	}

}
