package convex.gui.peer;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeoutException;
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
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.BasicWalletEntry;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.init.Init;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.ThreadUtils;
import convex.core.util.Utils;
import convex.gui.components.AbstractGUI;
import convex.gui.components.account.AccountsPanel;
import convex.gui.components.models.StateModel;
import convex.gui.keys.KeyGenPanel;
import convex.gui.keys.KeyListPanel;
import convex.gui.peer.mainpanels.AboutPanel;
import convex.gui.peer.mainpanels.MessageFormatPanel;
import convex.gui.peer.mainpanels.PeersListPanel;
import convex.gui.peer.mainpanels.TorusPanel;
import convex.gui.utils.Toolkit;
import convex.peer.Server;
import convex.restapi.RESTServer;

@SuppressWarnings("serial")
public class PeerGUI extends AbstractGUI {

	private static final Logger log = LoggerFactory.getLogger(PeerGUI.class.getName());

	protected JFrame frame;
	
	public List<AKeyPair> KEYPAIRS=new ArrayList<>();
	private List<AccountKey> PEERKEYS;
			
	public static final int DEFAULT_NUM_PEERS=3;
	
	
	public State genesisState;
	private StateModel<State> latestState = StateModel.create(genesisState);
	public StateModel<Long> tickState = StateModel.create(0L);

	/**
	 * Launch the application.
	 * @param args Command line args
	 */
	public static void main(String[] args) {
		
		// TODO: Store config
		// Stores.setGlobalStore(EtchStore.create(new File("peers-shared-db")));

		// call to set up Look and Feel
		Toolkit.init();
		
		launchPeerGUI(DEFAULT_NUM_PEERS, AKeyPair.generate(),true);
	}

	public static void launchPeerGUI(int peerNum, AKeyPair genesis, boolean topLevel) {
		EventQueue.invokeLater(()->{
			try {
				PeerGUI manager = new PeerGUI(peerNum,genesis);
				JFrame frame = new JFrame();
				manager.frame=frame;
				frame.setTitle("Testnet Peer Manager");
				frame.setIconImage(Toolkit.getDefaultToolkit()
						.getImage(PeerGUI.class.getResource("/images/Convex.png")));
				frame.setBounds(200, 150, 1000, 800);
				Toolkit.closeIfFirstFrame(frame);

				frame.getContentPane().add(manager, BorderLayout.CENTER);
				frame.setVisible(true);

				frame.addWindowListener(new java.awt.event.WindowAdapter() {
			        public void windowClosing(WindowEvent winEvt) {
			        	// shut down peers gracefully
			    		manager.peerPanel.closePeers();
			    		manager.restServer.stop();
			        }
			    });

			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	/*
	 * Main component panel
	 */
	PeersListPanel peerPanel;
	KeyListPanel keyRingPanel;
	
	KeyGenPanel keyGenPanel;
	MessageFormatPanel messagePanel;
	JPanel accountsPanel;
	JTabbedPane tabs;
	RESTServer restServer;
	
	AKeyPair genesisKey;

	/**
	 * Create the application.
	 * @param genesis Genesis key pair
	 * @param peerNum Numer of peers to initialise in geneis
	 */
	public PeerGUI(int peerCount, AKeyPair genesis) {
		super ("Peer Manager");
		// Create key pairs for peers, use genesis key as first keypair
		genesisKey=genesis;
		for (int i=0; i<peerCount; i++) {
			KEYPAIRS.add(AKeyPair.generate());
		}
		KEYPAIRS.set(0, genesis);
		
		PEERKEYS=KEYPAIRS.stream().map(kp->kp.getAccountKey()).collect(Collectors.toList());
		genesisState=Init.createState(PEERKEYS);
		latestState = StateModel.create(genesisState);
		tickState = StateModel.create(0L);
		
		// launch local peers 
		peerPanel= new PeersListPanel(this);
		keyRingPanel = new KeyListPanel(null);

		peerPanel.launchAllPeers(this);
		
		Server first=peerList.firstElement().getLocalServer();
		ConvexLocal convex=Convex.connect(first);
		
		// Set up observability
		
		try {
			restServer=RESTServer.create(first);
			restServer.start();
		} catch (Exception t) {
			log.warn("Unable to start REST Server: ",t);
		}
		
		keyGenPanel = new KeyGenPanel(this);
		messagePanel = new MessageFormatPanel(this);
		accountsPanel = new AccountsPanel(convex,latestState);
		keyRingPanel.setConvex(convex);

		setLayout(new BorderLayout());

		tabs = new JTabbedPane();
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("Peers", peerPanel);
		tabs.add("Accounts", accountsPanel);
		tabs.add("Keyring", keyRingPanel);
		tabs.add("KeyGen", keyGenPanel);
		tabs.add("Message", messagePanel);
		// tabs.add("Actors", new ActorsPanel(this));
		tabs.add("Torus", new TorusPanel(this));
		tabs.add("About", new AboutPanel(convex));
		
		tabs.setSelectedComponent(peerPanel);
		



		ThreadUtils.runVirtual(updateThread);
	}

	private boolean updateRunning = true;

	private long cp = 0;


	private Runnable updateThread = new Runnable() {
		@Override
		public void run() {
			Thread.currentThread().setName("PeerGUI update thread");
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
					log.trace("Update thread interrupted, presumably shutting down");
					updateRunning=false;
				}
			}
			log.debug("GUI Peer Manager update thread ending");
		}
	};

	protected DefaultListModel<ConvexLocal> peerList = new DefaultListModel<ConvexLocal>();

	public DefaultListModel<ConvexLocal> getPeerList() {
		return peerList;
	}

	@Override
	public void finalize() {
		// terminate the update thread if needed
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

	public KeyListPanel getWalletPanel() {
		return keyRingPanel;
	}

	/**
	 * Builds a connection to the peer network
	 * @param address Address for connection
	 * @param kp Key Pair for connection
	 * @return Convex connection instance
	 * @throws IOException If IO error occurs during connection attempt
	 * @throws TimeoutException If attempt to connect times out
	 */
	public Convex makeConnection(Address address,AKeyPair kp) throws IOException, TimeoutException {
		InetSocketAddress host = getDefaultConvex().getHostAddress();
		return Convex.connect(host,address, kp);
	}

	public State getLatestState() {
		return latestState.getValue();
	}

	public StateModel<State> getStateModel() {
		return latestState;
	}

	public ConvexLocal getDefaultConvex() {
		return peerList.getElementAt(0);
	}
	
	public Convex getClientConvex(Address contract) {
		return Convex.connect(getPrimaryServer(),contract,null);
	}

	public Convex connectClient(Address address, AKeyPair keyPair) {
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
	
	private static long maxBlock=0;
	public static long getMaxBlockCount() {
		return maxBlock;
	}

	public Server getRandomServer() {
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
	
	public Server getPrimaryServer() {
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


	public void addWalletEntry(BasicWalletEntry we) {
		keyRingPanel.addWalletEntry(we);
	}


}