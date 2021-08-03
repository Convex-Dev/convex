package convex.gui.manager;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Order;
import convex.core.Peer;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.WalletEntry;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.init.Init;
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

@SuppressWarnings("serial")
public class PeerGUI extends JPanel {

	private static final Logger log = LoggerFactory.getLogger(PeerGUI.class.getName());

	private static JFrame frame;
	
	public static List<AKeyPair> KEYPAIRS=new ArrayList<>();
			
	private static final int NUM_PEERS=3;
	
	static {
		for (int i=0; i<NUM_PEERS; i++) {
			KEYPAIRS.add(AKeyPair.generate());
		}
	}
	
	public static List<AccountKey> PEERKEYS=KEYPAIRS.stream().map(kp->kp.getAccountKey()).collect(Collectors.toList());
	
	public static State genesisState=Init.createState(PEERKEYS);
	private static StateModel<State> latestState = StateModel.create(genesisState);
	public static StateModel<Long> tickState = StateModel.create(0L);

	public static long maxBlock = 0;

	/**
	 * Launch the application.
	 * @param args Command line args
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		// TODO: Store config
		// Stores.setGlobalStore(EtchStore.create(new File("peers-shared-db")));

		// call to set up Look and Feel
		convex.gui.utils.Toolkit.init();

		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				try {
					PeerGUI.frame = new JFrame();
					frame.setTitle("Convex Peer Manager");
					frame.setIconImage(Toolkit.getDefaultToolkit()
							.getImage(PeerGUI.class.getResource("/images/Convex.png")));
					frame.setBounds(100, 100, 1024, 768);
					frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

					PeerGUI window = new PeerGUI();
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
	public PeerGUI() {
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

		// launch local peers for testing
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
					Thread.sleep(100);
					tickState.setValue(tickState.getValue()+1);
					
					java.util.List<PeerView> peerViews = peerPanel.getPeerViews();
					peerPanel.repaint();
					State latest = latestState.getValue();
					for (PeerView s : peerViews) {
						s.checkPeer();

						Server serv=s.peerServer;
						if (serv==null) continue;

						Peer p = serv.getPeer();
						if (p==null) continue;

						Order order=p.getPeerOrder();
						if (order==null) continue; // not an active peer?
						maxBlock = Math.max(maxBlock, order.getBlockCount());

						long pcp = p.getConsensusPoint();
						if (pcp > cp) {
							cp = pcp;
							//String ls="PeerGUI Consensus State update detected at depth "+cp;
							//System.err.println(ls);
							latest = p.getConsensusState();
							
						}
					}
					latestState.setValue(latest); // trigger peer view repaints etc.

				} catch (InterruptedException e) {
					//
					log.warn("Update thread interrupted abnormally: "+e.getMessage());
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
	 * @param address Address for connection
	 * @param kp Key Pair for connection
	 * @return Convex connection instance
	 * @throws IOException
	 * @throws TimeoutException 
	 */
	public static Convex makeConnection(Address address,AKeyPair kp) throws IOException, TimeoutException {
		InetSocketAddress host = getDefaultPeer().getHostAddress();
		return Convex.connect(host,address, kp);
	}

	/**
	 * Executes a transaction using the given Wallet
	 *
	 * @param code Code to execute
	 * @param we Wallet to use
	 * @return Future for Result
	 */
	public static CompletableFuture<Result> execute(WalletEntry we, ACell code) {
		Address address = we.getAddress();
		AccountStatus as = getLatestState().getAccount(address);
		long sequence = as.getSequence() + 1;
		ATransaction trans = Invoke.create(address,sequence, code);
		return execute(we,trans);
	}

	/**
	 * Executes a transaction using the given Wallet
	 *
	 * @param we Wallet to use
	 * @param trans Transaction to execute
	 * @return Future for Result
	 */
	public static CompletableFuture<Result> execute(WalletEntry we, ATransaction trans) {
		try {
			AKeyPair kp = we.getKeyPair();
			Convex convex = makeConnection(we.getAddress(),kp);
			CompletableFuture<Result> fr= convex.transact(trans);
			log.trace("Sent transaction: {}",trans);
			return fr;
		} catch (IOException | TimeoutException e) {
			throw Utils.sneakyThrow(e);
		}
	}

	/**
	 * Executes a transaction using the given Wallet
	 *
	 * @param we Wallet to use
	 * @param trans Transaction to execute
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

	public static Address getUserAddress(int i) {
		return Init.getGenesisPeerAddress(i);
	}
	
	public static AKeyPair getUserKeyPair(int i) {
		return KEYPAIRS.get(i);
	}

	public static Address getGenesisAddress() {
		return Init.getGenesisAddress();
	}
}
