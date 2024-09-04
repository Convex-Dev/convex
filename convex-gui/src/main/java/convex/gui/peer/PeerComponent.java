package convex.gui.peer;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;

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
import convex.etch.EtchStore;
import convex.gui.client.ConvexClient;
import convex.gui.components.BaseImageButton;
import convex.gui.components.BaseListComponent;
import convex.gui.components.CodeLabel;
import convex.gui.components.DropdownMenu;
import convex.gui.components.Identicon;
import convex.gui.etch.EtchWindow;
import convex.gui.models.StateModel;
import convex.gui.server.PeerWindow;
import convex.gui.state.StateExplorer;
import convex.gui.utils.Toolkit;
import convex.gui.wallet.WalletApp;
import convex.peer.ConnectionManager;
import convex.peer.Server;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class PeerComponent extends BaseListComponent {

	public ConvexLocal convex;
	JTextArea description;

	public void launchPeerWindow(ConvexLocal peer) {
		PeerWindow pw = new PeerWindow(peer);
		pw.run();
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

		setLayout(new MigLayout());

		// setPreferredSize(new Dimension(1000, 90));

		// Convex Button
		JButton button = new BaseImageButton(Toolkit.CONVEX);
		button.setFocusable(false);
		button.addActionListener(e -> {
			launchPeerWindow(this.convex);
		});
		button.setToolTipText("Launch Peer management window for this peer server");
		add(button, "dock west");
		
		//////////////////////////////////
		// Central area

		JPanel centralPanel = new JPanel();
		centralPanel.setLayout(new MigLayout("fill, wrap 2","[][grow]")); 
		
		Server server=convex.getLocalServer();
		AccountKey peerKey=server.getPeerKey();
		
		{ // Identicon / peer key heading row
			Identicon identicon=new Identicon(peerKey,Toolkit.IDENTICON_SIZE_LARGE);
			centralPanel.add(identicon);
			CodeLabel peerKeyLabel=(new CodeLabel("0x"+peerKey.toChecksumHex()));
			peerKeyLabel.setToolTipText("Public key of the peer.");
			centralPanel.add(peerKeyLabel,"span");
		}
		
		{ // Description of peer status
			description = new CodeLabel(getPeerDescription());
			description.setFont(Toolkit.MONO_FONT);
			description.setEditable(false);
			description.setBorder(null);
			description.setBackground(null);
			centralPanel.add(description, "span 2");
			add(centralPanel,"dock center");
		}
		
		//////////////////////////////////
		// Settings Popup menu for peer
		JPopupMenu popupMenu = new JPopupMenu();

		JMenuItem closeButton = new JMenuItem("Shutdown Peer",Toolkit.menuIcon(0xe8ac));
		closeButton.addActionListener(e -> {
			server.shutdown();
		});
		popupMenu.add(closeButton);

		JMenuItem exploreButton = new JMenuItem("Explore state",Toolkit.menuIcon(0xe97a));
		exploreButton.addActionListener(e -> {
			launchExploreWindow(convex);
		});
		popupMenu.add(exploreButton);

		if (server.getStore() instanceof EtchStore) {
			JMenuItem storeButton = new JMenuItem("Explore Etch store",Toolkit.menuIcon(0xf80e));
			storeButton.addActionListener(e -> {
				launchEtchWindow(convex);
			});
			popupMenu.add(storeButton);
		}
		
		JMenuItem killConn = new JMenuItem("Kill Connections",Toolkit.menuIcon(0xe16f));
		killConn.addActionListener(e -> {
			server.getConnectionManager().closeAllConnections();
		});
		popupMenu.add(killConn);

		JMenuItem replButton = new JMenuItem("Launch REPL",Toolkit.menuIcon(0xeb8e));
		replButton.addActionListener(e -> launchPeerWindow(this.convex));
		popupMenu.add(replButton);
		
		JMenuItem walletButton = new JMenuItem("Open controller Wallet",Toolkit.menuIcon(0xe850));
		walletButton.addActionListener(e -> {
			new WalletApp(convex).run();
		});
		popupMenu.add(walletButton);


		DropdownMenu dm = new DropdownMenu(popupMenu);
		add(dm, "dock east");

		////////////////////////////////////////////////
		// Block view at bottom
		
		JPanel blockView = new BlockViewComponent(convex);
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
		add(blockView, "dock south");

		
		// Final stuff
		updateDescription();
	}

	protected void updateDescription() {
		String current=description.getText();
		String updated=getPeerDescription();
		if (Objects.equals(updated, current)) return;
		
		// Set text while maintaining selection
		int ss=description.getSelectionStart();
		int se=description.getSelectionEnd();
		description.setText(updated);
		description.select(ss, se);
	}

	protected void launchClientWindow(Convex peer) {
		try {
			Convex convex = ConvexRemote.connect(peer.getHostAddress());
			Address addr=peer.getAddress();
			AKeyPair kp=peer.getKeyPair();;
			convex.setAddress(addr,kp);
			new ConvexClient(convex).run();
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
			// sb.append("0x"+paddr.toChecksumHex()+"\n");
			if (server.isLive()) {
				sb.append("Local peer on port: " + server.getPort() + "\n");
				// before:  + " with store "+server.getStore().shortName()+"\n"
			} else {
				sb.append("Inactive Peer\n");
			}
			PeerStatus ps=state.getPeer(paddr);
			if (ps!=null) {
				sb.append("Controller: "+ps.getController());
				sb.append("   ");
				sb.append("Stake: "+Text.toFriendlyBalance(ps.getPeerStake()));
				sb.append("   ");
				sb.append("Delegated Stake: "+Text.toFriendlyBalance(ps.getDelegatedStake()));
				sb.append("   ");
			} else {
				sb.append("Not currently a registered peer    ");
			}
			ConnectionManager cm=server.getConnectionManager();
			sb.append("C: "+cm.getConnectionCount());				
		} else if (convex != null) {
			sb.append(convex.toString());
		} else {
			sb.append("Unknown");
		}
		return sb.toString();
	}
}
