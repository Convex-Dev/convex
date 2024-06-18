package convex.gui.peer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.Order;
import convex.core.Peer;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.init.Init;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.ThreadUtils;
import convex.core.util.Utils;
import convex.gui.components.AbstractGUI;
import convex.gui.components.Toast;
import convex.gui.components.account.AccountsPanel;
import convex.gui.components.account.KeyPairCombo;
import convex.gui.keys.KeyGenPanel;
import convex.gui.keys.KeyRingPanel;
import convex.gui.models.StateModel;
import convex.gui.tools.MessageFormatPanel;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import convex.peer.API;
import convex.peer.Server;
import convex.restapi.RESTServer;
import net.miginfocom.swing.MigLayout;

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
				frame.setTitle("Peer Manager");
				frame.setIconImage(Toolkit.getDefaultToolkit()
						.getImage(PeerGUI.class.getResource("/images/Convex.png")));
				frame.setBounds(200, 150, 1000, 800);
				Toolkit.closeIfFirstFrame(frame);

				frame.getContentPane().add(manager, BorderLayout.CENTER);
				frame.setVisible(true);

				frame.addWindowListener(new java.awt.event.WindowAdapter() {
			        public void windowClosing(WindowEvent winEvt) {
			        	// shut down peers gracefully
			    		manager.peerPanel.manager.closePeers();
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
	KeyRingPanel keyRingPanel;
	
	KeyGenPanel keyGenPanel;
	MessageFormatPanel messagePanel;
	JPanel accountsPanel;
	JTabbedPane tabs;
	RESTServer restServer;
	
	AKeyPair genesisKey;

	/**
	 * Create the application.
	 * @param genesis Genesis key pair
	 * @param peerCount number of peers to initialise in genesis
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
		keyRingPanel = new KeyRingPanel();

		launchAllPeers();
		
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
		messagePanel = new MessageFormatPanel();
		accountsPanel = new AccountsPanel(convex,latestState);

		setLayout(new BorderLayout());

		tabs = new JTabbedPane();
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("Servers", peerPanel);
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
		log.info("Missing tab: " + title);
	}

	public KeyRingPanel getWalletPanel() {
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
		int n=peerList.size();
		for (int i=0; i<n; i++) {
			ConvexLocal c= peerList.get(i);
			if (c.getLocalServer().isLive()) return c;
		}
		throw new IllegalStateException("No live peers!");
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
			if ((s!=null)&&(s.isLive())) {
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
			if ((s!=null)&&s.isLive()) {
				return s;
			}
		}
		return null;
	}

	public void launchAllPeers() {
		try {
			List<Server> serverList = API.launchLocalPeers(KEYPAIRS,genesisState);
			for (Server server: serverList) {
				ConvexLocal convex=Convex.connect(server, server.getPeerController(), server.getKeyPair());
				peerList.addElement(convex);
				
				// initial wallet list
		        HotWalletEntry we = HotWalletEntry.create(server.getKeyPair());
				KeyRingPanel.addWalletEntry(we);
			}
		} catch (Exception e) {
			if (e instanceof ClosedChannelException) {
				// Ignore
			} else {
				throw(e);
			}		
		}
	}

	public void launchExtraPeer() {
		AKeyPair kp=AKeyPair.generate();
		
		try {
			Server base=getPrimaryServer();
			ConvexLocal convex=getDefaultConvex();
			Address a= convex.createAccountSync(kp.getAccountKey());
			long amt=convex.getBalance()/10;
			convex.transferSync(a, amt);
			
			KeyRingPanel.addWalletEntry(HotWalletEntry.create(kp));
			
			// Set up Peer in base server
			convex=Convex.connect(base, a, kp);
			AccountKey key=kp.getAccountKey();
			Result rcr=convex.transactSync("(create-peer "+key+" "+amt/2+")");
			if (rcr.isError()) {
				throw new Exception(rcr.toString());
			}
			
			HashMap<Keyword, Object> config=new HashMap<>();
			config.put(Keywords.KEYPAIR, kp);
			config.put(Keywords.CONTROLLER, a);
			config.put(Keywords.STATE, genesisState);
			Server server=API.launchPeer(config);
			server.getConnectionManager().connectToPeer(base.getHostAddress());
			server.setHostname("localhost:"+server.getPort());
			base.getConnectionManager().connectToPeer(server.getHostAddress());
			
			convex=Convex.connect(server, a, kp);
			peerList.addElement(convex);
		} catch (Exception e) {
			Toast.display(this, "Error launching extra peer: "+e.getMessage(),Color.RED);
			e.printStackTrace();
		}
	}

	public void closePeers() {
		DefaultListModel<ConvexLocal> peerList = getPeerList();
		int n = peerList.getSize();
		for (int i = 0; i < n; i++) {
			Convex p = peerList.getElementAt(i);
			try {
				p.getLocalServer().close();
			} catch (Exception e) {
				// ignore
			}
		}
	}

	public static void runLaunchDialog(JComponent parent) {
		JPanel pan=new JPanel();
		pan.setLayout(new MigLayout("fill,wrap 2","","[fill]10[fill]"));
		
		pan.add(Toolkit.makeNote("Select a number of peers to include in the genesis state and launch initially. More can be added later. 3-5 recommended for local devnet testing"),
				"grow,span 2");
		pan.add(new JLabel("Number of Peers:"));
		JSpinner peerCountSpinner = new JSpinner();
		// Note: about 300 max number of clients before hitting juice limits for account creation
		peerCountSpinner.setModel(new SpinnerNumberModel(PeerGUI.DEFAULT_NUM_PEERS, 1, 100, 1));
		pan.add(peerCountSpinner);

		
		pan.add(Toolkit.makeNote("Select genesis key for the network. The genesis key will be the key used for the first peer and initial governance accounts."),
				"grow,span 2");
		pan.add(new JLabel("Genesis Key:   "));
		AKeyPair kp=AKeyPair.generate();
		KeyPairCombo keyField=KeyPairCombo.create(kp);

		pan.add(keyField);
		pan.add(new JPanel());
		
		JButton randomise=new JButton("Randomise",SymbolIcon.get(0xe863,Toolkit.SMALL_ICON_SIZE)); 
		randomise.addActionListener(e->{
			AKeyPair newKP=AKeyPair.generate();
			// System.err.println("Generated key "+newKP.getAccountKey());
			// Note we go to the model directly, JComboBox doeesn't like
			// setting a selected item to something not in the list when not editable
			keyField.getModel().setSelectedItem(HotWalletEntry.create(newKP));
		});
		pan.add(randomise);


		int result = JOptionPane.showConfirmDialog(parent, pan, 
	               "Enter Launch Details", 
	               JOptionPane.OK_CANCEL_OPTION, 
	               JOptionPane.QUESTION_MESSAGE,
	               SymbolIcon.get(0xeb9b,72));
	    if (result == JOptionPane.OK_OPTION) {
	    	try {
	    		int numPeers=(Integer)peerCountSpinner.getValue();
	    		AWalletEntry we=keyField.getWalletEntry();
	    		if (we==null) throw new Exception("No key pair selected");
	    		
	       		kp=we.getKeyPair();
	    		if (kp==null) throw new Exception("Invalid Genesis Key!");
	    		PeerGUI.launchPeerGUI(numPeers, kp,false);
	    	} catch (Exception e) {
	    		Toast.display(parent, "Launch Failed: "+e.getMessage(), Color.RED);
	    		e.printStackTrace();
	    	}
	    }
		
		
	}

}