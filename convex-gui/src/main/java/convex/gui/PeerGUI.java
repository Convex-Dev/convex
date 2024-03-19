package convex.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexLocal;
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
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.gui.components.models.StateModel;
import convex.gui.manager.mainpanels.AboutPanel;
import convex.gui.manager.mainpanels.AccountsPanel;
import convex.gui.manager.mainpanels.HomePanel;
import convex.gui.manager.mainpanels.KeyGenPanel;
import convex.gui.manager.mainpanels.MessageFormatPanel;
import convex.gui.manager.mainpanels.PeersListPanel;
import convex.gui.manager.mainpanels.TorusPanel;
import convex.gui.manager.mainpanels.WalletPanel;
import convex.peer.Server;
import convex.restapi.RESTServer;

@SuppressWarnings("serial")
public class PeerGUI extends JPanel {

	private static final Logger log = LoggerFactory.getLogger(PeerGUI.class.getName());

	private static JFrame frame;
	
	public List<AKeyPair> KEYPAIRS=new ArrayList<>();
	private List<AccountKey> PEERKEYS;
			
	private static final int DEFAULT_NUM_PEERS=3;
	
	
	public State genesisState;
	private StateModel<State> latestState = StateModel.create(genesisState);
	public StateModel<Long> tickState = StateModel.create(0L);

	public static long maxBlock = 0;

	static {
		convex.gui.utils.Toolkit.init();
	}

	/**
	 * Launch the application.
	 * @param args Command line args
	 */
	public static void main(String[] args) {
		
		// TODO: Store config
		// Stores.setGlobalStore(EtchStore.create(new File("peers-shared-db")));

		// call to set up Look and Feel	
		
		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				try {
					PeerGUI.frame = new JFrame();
					frame.setTitle("Convex Peer Manager");
					frame.setIconImage(Toolkit.getDefaultToolkit()
							.getImage(PeerGUI.class.getResource("/images/Convex.png")));
					frame.setBounds(100, 100, 1200, 900);
					frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

					PeerGUI window = new PeerGUI();
					frame.getContentPane().add(window, BorderLayout.CENTER);
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
	HomePanel homePanel;
	PeersListPanel peerPanel;
	WalletPanel walletPanel;
	KeyGenPanel keyGenPanel;
	MessageFormatPanel messagePanel;
	JPanel accountsPanel;
	JTabbedPane tabs;
	RESTServer restServer;

	/**
	 * Create the application.
	 */
	public PeerGUI() {
		int peerCount=DEFAULT_NUM_PEERS;
		for (int i=0; i<peerCount; i++) {
			KEYPAIRS.add(AKeyPair.generate());
		}
		PEERKEYS=KEYPAIRS.stream().map(kp->kp.getAccountKey()).collect(Collectors.toList());
		genesisState=Init.createState(PEERKEYS);
		latestState = StateModel.create(genesisState);
		tickState = StateModel.create(0L);
		
		peerPanel= new PeersListPanel(this);
		homePanel = new HomePanel();
		walletPanel = new WalletPanel(this);
		keyGenPanel = new KeyGenPanel(this);
		messagePanel = new MessageFormatPanel(this);
		accountsPanel = new AccountsPanel(this);

		setLayout(new BorderLayout());

		tabs = new JTabbedPane();
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("Home", homePanel);
		tabs.add("Peers", peerPanel);
		tabs.add("Wallet", getWalletPanel());
		tabs.add("Accounts", accountsPanel);
		tabs.add("KeyGen", keyGenPanel);
		tabs.add("Message", messagePanel);
		// tabs.add("Actors", new ActorsPanel(this));
		tabs.add("Torus", new TorusPanel(this));
		tabs.add("About", new AboutPanel(this));
		
		tabs.setSelectedComponent(peerPanel);
		
		// launch local peers for testing
		EventQueue.invokeLater(() -> {
			peerPanel.launchAllPeers(this);
			
			Server first=peerList.firstElement().getLocalServer();
			
			// Set up observability
			
			try {
				restServer=RESTServer.create(first);
				restServer.start();
			} catch (Exception t) {
				log.warn("Unable to start REST Server: ",t);
			}

		});

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
					
					java.util.List<ConvexLocal> peerViews = peerPanel.getPeerViews();
					peerPanel.repaint();
					State latest = latestState.getValue();
					for (ConvexLocal s : peerViews) {

						Server serv=s.getLocalServer();
						if (serv==null) continue;

						Peer p = serv.getPeer();
						if (p==null) continue;

						Order order=p.getPeerOrder();
						if (order==null) continue; // not an active peer?
						maxBlock = Math.max(maxBlock, order.getBlockCount());

						long pcp = p.getFinalityPoint();
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
			log.debug("GUI Peer Manager update thread ended");
		}
	}, "GUI Manager state update thread");

	public static DefaultListModel<ConvexLocal> peerList = new DefaultListModel<ConvexLocal>();



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
	 * @throws IOException If IO error occurs during connection attempt
	 * @throws TimeoutException If attempt to connect times out
	 */
	public static Convex makeConnection(Address address,AKeyPair kp) throws IOException, TimeoutException {
		InetSocketAddress host = getDefaultConvex().getHostAddress();
		return Convex.connect(host,address, kp);
	}

	/**
	 * Executes a transaction using the given Wallet
	 *
	 * @param code Code to execute
	 * @param we Wallet to use
	 * @return Future for Result
	 */
	public CompletableFuture<Result> execute(WalletEntry we, ACell code) {
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
	 * @param receiveAction Action to invoke when result is received
	 */
	public static void execute(WalletEntry we, ATransaction trans, Consumer<Result> receiveAction) {
		execute(we,trans).thenAcceptAsync(receiveAction);
	}

	public State getLatestState() {
		return latestState.getValue();
	}

	public static Component getFrame() {
		return frame;
	}

	public StateModel<State> getStateModel() {
		return latestState;
	}

	public static Convex getDefaultConvex() {
		return PeersListPanel.getFirst();
	}

	public static Address getUserAddress(int i) {
		return Init.getGenesisPeerAddress(i);
	}
	
	public AKeyPair getUserKeyPair(int i) {
		return KEYPAIRS.get(i);
	}

	public static Address getGenesisAddress() {
		return Init.getGenesisAddress();
	}

	public static Convex connectClient(Address address, AKeyPair keyPair) {
		try {
			return makeConnection(address,keyPair);
		} catch (IOException | TimeoutException e) {
			throw Utils.sneakyThrow(e);
		}
	}
	
	private static HashMap<Server,StateModel<Peer>> models=new HashMap<>();

	public static StateModel<Peer> getStateModel(Convex peer) {
		Server s=peer.getLocalServer();
		if (s!=null) {
			StateModel<Peer> model=models.get(s);
			if	(model!=null) return model;
			StateModel<Peer> newModel=StateModel.create(s.getPeer());
			s.getCVMExecutor().setUpdateHook(p->{
				AStore tempStore=Stores.current();
				try {
					Stores.setCurrent(s.getStore());
					newModel.setValue(p);
				} finally {
					Stores.setCurrent(tempStore);
				}
				// latestState.setValue(p.getConsensusState());
			});
			models.put(s, newModel);
			return newModel;
		}
		return null;
	}

	public static Server getRandomServer() {
		Server result=null;
		int n=peerList.getSize();
		int found=0;
		for (int i=0; i<n; i++) {
			ConvexLocal c=peerList.elementAt(i);
			Server s=c.getLocalServer();
			if (s!=null) {
				found+=1;
				if (Math.random()*found<=1.0) {
					result=s;
				}
			}
		}
		return result;
	}
	
	public static Server getPrimaryServer() {
		int n=peerList.getSize();
		for (int i=0; i<n; i++) {
			ConvexLocal c=peerList.elementAt(i);
			Server s=c.getLocalServer();
			if (s!=null) {
				return s;
			}
		}
		return null;
	}

	public static void runWithLatestState(Consumer<State> f) {
		AStore tempStore=Stores.current();
		try {
			Server s=getPrimaryServer();
			Stores.setCurrent(s.getStore());
			f.accept(s.getPeer().getConsensusState());
		} finally {
			Stores.setCurrent(tempStore);
		}
	}
	
	public static void runOnPrimaryServer(Consumer<Server> f) {
		Server s=getPrimaryServer();
		runOnServer(s,f);
	}

	public static void runOnServer(Server server,Consumer<Server> f) {
		AStore tempStore=Stores.current();
		try {
			Stores.setCurrent(server.getStore());
			f.accept(server);
		} finally {
			Stores.setCurrent(tempStore);
		}	
	}
}