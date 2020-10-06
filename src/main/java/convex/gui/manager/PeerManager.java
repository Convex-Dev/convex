package convex.gui.manager;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import convex.api.Convex;
import convex.core.Init;
import convex.core.Peer;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.WalletEntry;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.gui.components.PeerView;
import convex.gui.components.models.StateModel;
import convex.gui.manager.mainpanels.AboutPanel;
import convex.gui.manager.mainpanels.AccountsPanel;
import convex.gui.manager.mainpanels.ActorsPanel;
import convex.gui.manager.mainpanels.HomePanel;
import convex.gui.manager.mainpanels.KeyGenPanel;
import convex.gui.manager.mainpanels.MessageFormatPanel;
import convex.gui.manager.mainpanels.PeersListPanel;
import convex.gui.manager.mainpanels.WalletPanel;
import convex.peer.Server;
import etch.EtchStore;

@SuppressWarnings("serial")
public class PeerManager extends JPanel {

	private static final Logger log = Logger.getLogger(PeerManager.class.getName());

	private static JFrame frame;

	private static StateModel<State> latestState = StateModel.create(Init.STATE);

	public static long maxBlock = 0;

	/**
	 * Launch the application.
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException {
		Stores.setGlobalStore(EtchStore.create(new File("peers-shared-db")));
		
		// call to set up Look and Feel
		convex.gui.utils.Toolkit.init();
		
		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				try {
					PeerManager.frame = new JFrame();
					frame.setTitle("Convex Peer Manager");
					frame.setIconImage(Toolkit.getDefaultToolkit()
							.getImage(PeerManager.class.getResource("/images/Convex.png")));
					frame.setBounds(100, 100, 1024, 768);
					frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
					PeerManager window = new PeerManager();
					frame.getContentPane().add(window, BorderLayout.CENTER);
					frame.pack();
					frame.setVisible(true);
					
					frame.addWindowListener(new java.awt.event.WindowAdapter() {
				        public void windowClosing(WindowEvent winEvt) {
				        	// shut down peers gracefully
				    		window.peerPanel.closePeers();
				        }
				    });
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	/*
	 * Main component panel
	 */
	JPanel panel = new JPanel();

	HomePanel homePanel = new HomePanel();
	PeersListPanel peerPanel;
	WalletPanel walletPanel = new WalletPanel();
	KeyGenPanel keyGenPanel = new KeyGenPanel(this);
	MessageFormatPanel messagePanel = new MessageFormatPanel(this);
	AboutPanel aboutPanel = new AboutPanel();
	JTabbedPane tabs = new JTabbedPane();
	JPanel mainPanel = new JPanel();
	JPanel accountsPanel = new AccountsPanel(this);

	/**
	 * Create the application.
	 */
	public PeerManager() {
		peerPanel= new PeersListPanel(this);
		
		setLayout(new BorderLayout());
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("Home", homePanel);
		tabs.add("Peers", peerPanel);
		tabs.add("Wallet", getWalletPanel());
		tabs.add("Accounts", accountsPanel);
		tabs.add("KeyGen", keyGenPanel);
		tabs.add("Message", messagePanel);
		tabs.add("Actors", new ActorsPanel(this));
		tabs.add("About", aboutPanel);

		// launch a local peer for testing
		EventQueue.invokeLater(() -> peerPanel.launchAllPeers(this));

		updateThread.start();
	}

	private boolean updateRunning = true;

	private long cp = 0;

	private Thread updateThread = new Thread(new Runnable() {
		@Override
		public void run() {
			while (updateRunning) {
				try {
					Thread.sleep(30);
					java.util.List<PeerView> servers = peerPanel.getPeerViews();
					State latest = latestState.getValue();
					for (PeerView s : servers) {
						Server serv=s.peerServer;
						if (serv==null) continue;
						
						Peer p = serv.getPeer();
						if (p==null) continue;

						maxBlock = Math.max(maxBlock, p.getPeerOrder().getBlockCount());

						long pcp = p.getConsensusPoint();
						if (pcp > cp) {
							cp = pcp;
							latest = p.getConsensusState();
						}
					}
					
					latestState.setValue(latest);
				} catch (Exception e) {
					//
					log.warning("Update thread interrupted abnormally: "+e.getMessage());
					e.printStackTrace();
					Thread.currentThread().interrupt();
				}
			}
			log.info("Manager update thread ended");
		}
	}, "GUI Manager state update thread");

	@Override
	public void finalize() {
		// terminate the update thread
		updateRunning = false;
	}

	public void switchPanel(String title) {
		int n = tabs.getTabCount();
		for (int i = 0; i < n; i++) {
			if (tabs.getTitleAt(i).contentEquals(title)) {
				tabs.setSelectedIndex(i);
				return;
			}
		}
		System.err.println("Missing tab: " + title);
	}

	public WalletPanel getWalletPanel() {
		return walletPanel;
	}

	/**
	 * Builds a connection to the peer network
	 * @throws IOException 
	 */
	public static Convex makeConnection(AKeyPair kp) throws IOException {
		InetSocketAddress host = getDefaultPeer().getHostAddress();
		return Convex.connect(host, kp);
	}

	/**
	 * Executes a transaction using the given Wallet
	 * 
	 * @param code
	 * @param receiveAction
	 */
	public static CompletableFuture<Result> execute(WalletEntry we, Object code) {
		Address address = we.getAddress();
		AccountStatus as = getLatestState().getAccount(address);
		long sequence = as.getSequence() + 1;
		ATransaction trans = Invoke.create(sequence, code);
		return execute(we,trans);
	}
	
	/**
	 * Executes a transaction using the given Wallet
	 * 
	 * @param code
	 * @param receiveAction
	 */
	public static CompletableFuture<Result> execute(WalletEntry we, ATransaction trans) {
		try {
			AKeyPair kp = we.getKeyPair();
			Convex convex = makeConnection(kp);
			CompletableFuture<Result> fr= convex.transact(trans);
			log.finer("Sent transaction: "+trans.toString());
			return fr;
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
	}

	/**
	 * Executes a transaction using the given Wallet
	 * 
	 * @param code
	 * @param receiveAction
	 */
	public static void execute(WalletEntry we, ATransaction trans, Consumer<Result> receiveAction) {
		execute(we,trans).thenAcceptAsync(receiveAction);
	}

	public static State getLatestState() {
		return latestState.getValue();
	}

	public static Component getFrame() {
		return frame;
	}

	public static StateModel<State> getStateModel() {
		return latestState;
	}

	public static PeerView getDefaultPeer() {
		return PeersListPanel.getFirst();
	}
}
