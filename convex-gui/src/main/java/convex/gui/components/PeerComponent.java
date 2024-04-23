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
import convex.api.ConvexLocal;
import convex.api.ConvexRemote;
import convex.core.Peer;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.PeerStatus;
import convex.core.text.Text;
import convex.gui.client.ConvexClient;
import convex.gui.components.models.StateModel;
import convex.gui.etch.EtchWindow;
import convex.gui.peer.PeerGUI;
import convex.gui.peer.windows.PeerWindow;
import convex.gui.peer.windows.state.StateExplorer;
import convex.gui.utils.Toolkit;
import convex.peer.ConnectionManager;
import convex.peer.Server;
import etch.EtchStore;

@SuppressWarnings("serial")
public class PeerComponent extends BaseListComponent {

	public ConvexLocal convex;
	JTextArea description;

	public void launchPeerWindow(ConvexLocal peer) {
		try {
			PeerWindow pw = new PeerWindow(peer);
			pw.run();
		} catch (Exception e) {
			// Ignore
		}
	}

	public void launchEtchWindow(ConvexLocal peer) {
		EtchWindow ew = new EtchWindow(peer);
		ew.run();
	}

	public void launchExploreWindow(Convex peer) {
		Server s = peer.getLocalServer();
		ACell p = s.getPeer().getConsensusState();
		StateExplorer pw = new StateExplorer(p);
		pw.run();
	}

	public PeerComponent(ConvexLocal value) {
		this.convex = value;

		setLayout(new BorderLayout(0, 0));

		// setPreferredSize(new Dimension(1000, 90));

		JButton button = new JButton("");
		button.setBorder(null);
		add(button, BorderLayout.WEST);
		button.setIcon(Toolkit.CONVEX);
		button.addActionListener(e -> {
			launchPeerWindow(this.convex);
		});
		button.setToolTipText("Launch Peer management window");

		JPanel panel = new JPanel();
		panel.setBorder(new EmptyBorder(5, 5, 5, 5));
		add(panel);
		panel.setLayout(new BorderLayout(0, 0));

		description = new JTextArea((convex == null) ? "No peer" : convex.toString());
		description.setFont(Toolkit.SMALL_MONO_FONT);
		description.setEditable(false);
		description.setBorder(null);
		description.setBackground(null);
		panel.add(description, BorderLayout.CENTER);

		// Setup popup menu for peer
		JPopupMenu popupMenu = new JPopupMenu();
		Server server=convex.getLocalServer();
		if (server!=null) {
			JMenuItem closeButton = new JMenuItem("Shutdown Peer");
			closeButton.addActionListener(e -> {
				try {
					server.shutdown();
				} catch (Exception e1) {
					// ignore
				}
			});
			popupMenu.add(closeButton);

			JMenuItem exploreButton = new JMenuItem("Explore state");
			exploreButton.addActionListener(e -> {
				launchExploreWindow(convex);
			});
			popupMenu.add(exploreButton);

			if (server.getStore() instanceof EtchStore) {
				JMenuItem storeButton = new JMenuItem("Explore Etch store");
				storeButton.addActionListener(e -> {
					launchEtchWindow(convex);
				});
				popupMenu.add(storeButton);
			}
			
			
			JMenuItem killConn = new JMenuItem("Kill Connections");
			killConn.addActionListener(e -> {
				server.getConnectionManager().closeAllConnections();
			});
			popupMenu.add(killConn);
			
		} else {
			JMenuItem closeButton = new JMenuItem("Close connection");
			closeButton.addActionListener(e -> {
				convex.close();
			});
			popupMenu.add(closeButton);
		}

		JMenuItem replButton = new JMenuItem("Launch REPL");
		replButton.addActionListener(e -> launchPeerWindow(this.convex));
		popupMenu.add(replButton);
		
		JMenuItem clientButton = new JMenuItem("Connect Client");
		clientButton.addActionListener(e -> launchClientWindow(convex));
		popupMenu.add(clientButton);

		JPanel blockView = new BlockViewComponent(convex);
		add(blockView, BorderLayout.SOUTH);

		DropdownMenu dm = new DropdownMenu(popupMenu);
		add(dm, BorderLayout.EAST);
		
		StateModel<Peer> model=PeerGUI.getStateModel(convex);
		if (model!=null) {
			model.addPropertyChangeListener(e->{
				blockView.repaint();
				description.setText(getPeerDescription());
			});
			
			model.addPropertyChangeListener(e->{
				updateDescription();
			});
		}
		updateDescription();
	}

	protected void updateDescription() {
		// Set text while maintaining selection
		int ss=description.getSelectionStart();
		int se=description.getSelectionEnd();
		description.setText(getPeerDescription());
		description.select(ss, se);
	}

	private void launchClientWindow(Convex peer) {
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
	
	public String getPeerDescription() {
		StringBuilder sb=new StringBuilder();
		Server server=convex.getLocalServer();
		if (server != null) {
			State state=server.getPeer().getConsensusState();
			AccountKey paddr=server.getPeerKey();
			sb.append("0x"+paddr.toChecksumHex()+"\n");
			sb.append("Local peer on: " + server.getHostAddress() + " with store "+server.getStore()+"\n");
			
			PeerStatus ps=state.getPeer(paddr);
			if (ps!=null) {
				sb.append("Peer Stake:  "+Text.toFriendlyBalance(ps.getPeerStake()));
				sb.append("    ");
				sb.append("Delegated Stake:  "+Text.toFriendlyBalance(ps.getDelegatedStake()));
			}
			ConnectionManager cm=server.getConnectionManager();
			sb.append("\n");
			sb.append("Connections: "+cm.getConnectionCount());				
		} else if (convex != null) {
			sb.append(convex.toString());
		} else {
			sb.append("Unknown");
		}
		return sb.toString();
	}
}
