package convex.gui.peer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
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
import convex.core.Result;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.init.Init;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.ThreadUtils;
import convex.gui.components.AbstractGUI;
import convex.gui.components.Toast;
import convex.gui.components.account.AccountsPanel;
import convex.gui.keys.KeyGenPanel;
import convex.gui.keys.KeyRingPanel;
import convex.gui.models.StateModel;
import convex.gui.tools.MessageFormatPanel;
import convex.gui.utils.Toolkit;
import convex.peer.API;
import convex.peer.PeerException;
import convex.peer.Server;
import convex.restapi.RESTServer;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class PeerGUI extends AbstractGUI {

	private static final Logger log = LoggerFactory.getLogger(PeerGUI.class.getName());

	protected JFrame frame;
	
			
	public static final int DEFAULT_NUM_PEERS=3;
	
	
	public State genesisState;
	private StateModel<State> latestState = StateModel.create(genesisState);
	public StateModel<Long> tickState = StateModel.create(0L);
	
	protected DefaultListModel<ConvexLocal> peerList = new DefaultListModel<ConvexLocal>();


	/**
	 * Launch the application.
	 * @param args Command line arguments
	 * @throws Exception in case of failure
	 */
	public static void main(String[] args) throws Exception {
		// call to set up Look and Feel
		Toolkit.init();
		
		PeerGUI gui=launchPeerGUI(DEFAULT_NUM_PEERS, AKeyPair.generate());
		gui.waitForClose();
		System.exit(0);
	}

	public static PeerGUI launchPeerGUI(int peerNum, AKeyPair genesisKey) throws InterruptedException, PeerException {
		PeerGUI manager = create(peerNum,genesisKey);
		manager.run();
		return manager;
	}
	
	public static PeerGUI launchPeerGUI(InetSocketAddress sa, AWalletEntry we) throws InterruptedException, PeerException {
		DefaultListModel<ConvexLocal> peerList=new DefaultListModel<>();
		
		HashMap<Keyword, Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,we.getKeyPair());
		config.put(Keywords.SOURCE,sa);
		Server server=API.launchPeer(config);
		ConvexLocal convex=ConvexLocal.connect(server);
		peerList.addElement(convex);
		PeerGUI manager =  new PeerGUI(peerList);
		manager.run();
		return manager;		
	}
	
	public static PeerGUI launchPeerGUI(Server server) throws InterruptedException, PeerException {
		DefaultListModel<ConvexLocal> peerList=new DefaultListModel<>();

		server.launch();
		ConvexLocal convex=ConvexLocal.connect(server);
		peerList.addElement(convex);
		PeerGUI manager =  new PeerGUI(peerList);
		manager.run();
		return manager;		
	}

	public static PeerGUI create(int peerCount, AKeyPair genesisKey) throws PeerException {
		DefaultListModel<ConvexLocal> peerList=launchAllPeers(peerCount,genesisKey);
		return new PeerGUI(peerList);
	}
	

	private static DefaultListModel<ConvexLocal> launchAllPeers(int peerCount, AKeyPair genesisKey) throws PeerException {
		List<AKeyPair> KEYPAIRS=new ArrayList<>();
		List<AccountKey> PEERKEYS;
		for (int i=0; i<peerCount; i++) {
			KEYPAIRS.add(AKeyPair.generate());
		}

        HotWalletEntry gwe = HotWalletEntry.create(genesisKey,"Genesis key pair (temp)");
		KeyRingPanel.addWalletEntry(gwe);

		AccountKey genPK=genesisKey.getAccountKey();
		
		PEERKEYS=KEYPAIRS.stream().map(kp->kp.getAccountKey()).collect(Collectors.toList());
		State genesisState=Init.createState(genPK,genPK,PEERKEYS);

		try {
			DefaultListModel<ConvexLocal> peerList=new DefaultListModel<>();
			List<Server> serverList = API.launchLocalPeers(KEYPAIRS,genesisState);
			for (Server server: serverList) {
				Address controller=server.getPeerController();
				AKeyPair kp=server.getControllerKey();
				ConvexLocal convex=Convex.connect(server, controller, kp); 
				
				if (kp==null) {
					// Try to find alternative controller key in key ring if available
					kp=KeyRingPanel.findWalletEntry(convex).getKeyPair();
					convex.setKeyPair(kp);
				}
				
				peerList.addElement(convex);
				
				// initial wallet list
		        HotWalletEntry we = HotWalletEntry.create(server.getKeyPair(),"Peer key pair (temp)");
				KeyRingPanel.addWalletEntry(we);
			}
			return peerList;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new PeerException("Peer launch interrupted",e);
		}
	}
	/*
	 * Main component panel
	 */
	ServerListPanel serverPanel;
	KeyRingPanel keyRingPanel;
	
	KeyGenPanel keyGenPanel;
	MessageFormatPanel messagePanel;
	JPanel accountsPanel;
	JTabbedPane tabs;
	RESTServer restServer;
	

	/**
	 * Create the application.
	 * @param genesis Genesis key pair
	 * @param peerCount number of peers to initialise in genesis
	 * @throws PeerException If peer startup fails
	 */
	private PeerGUI(DefaultListModel<ConvexLocal> peerList) throws PeerException {
		super ("Peer Manager");
		this.peerList=peerList;
		
		// Create key pairs for peers, use genesis key as first keypair
		Server firstServer=peerList.get(0).getLocalServer();
		genesisState=firstServer.getPeer().getGenesisState();

		latestState = StateModel.create(genesisState);
		tickState = StateModel.create(0L);
		

		
		serverPanel= new ServerListPanel(this);
		keyRingPanel = new KeyRingPanel();

		
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
		tabs.setPreferredSize(new Dimension(1000,800));
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("Peer Servers", serverPanel);
		tabs.add("Accounts", accountsPanel);
		tabs.add("Keyring", keyRingPanel);
		tabs.add("KeyGen", keyGenPanel);
		tabs.add("Message", messagePanel);
		// tabs.add("Actors", new ActorsPanel(this));
		tabs.add("Torus", new TorusPanel(this));
		tabs.add("About", new AboutPanel(convex));
		
		tabs.setSelectedComponent(serverPanel);

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
					
					java.util.List<ConvexLocal> peerViews = serverPanel.getPeerViews();
					serverPanel.repaint();
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
					updateRunning=false;
					Thread.currentThread().interrupt(); // set interrupt flag since an interruption has occurred	
					log.trace("Update thread interrupted, presumably shutting down");
				}
			}
			log.debug("GUI Peer Manager update thread ending");
		}
	};


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
	 * @throws InterruptedException 
	 */
	public Convex makeConnection(Address address,AKeyPair kp) throws IOException, TimeoutException, InterruptedException {
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
		throw new IllegalStateException("No live peers we control!");
	}
	
	public Convex getClientConvex(Address contract) {
		return Convex.connect(getPrimaryServer(),contract,null);
	}

	public Convex connectClient(Address address, AKeyPair keyPair) throws IOException, TimeoutException, InterruptedException {
			return makeConnection(address,keyPair);
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


	public void launchExtraPeer(ConvexLocal source) {
		AKeyPair kp=AKeyPair.generate();
		
		try {
			Server base=source.getLocalServer();

			Address a= source.createAccountSync(kp.getAccountKey());
			long amt=source.getBalance()/10;
			source.transferSync(a, amt);
			
			KeyRingPanel.addWalletEntry(HotWalletEntry.create(kp,"Generated peer key"));
			
			// Set up Peer in base server
			ConvexLocal convex=Convex.connect(base, a, kp);
			AccountKey key=kp.getAccountKey();
			Result rcr=convex.transactSync("(create-peer "+key+" "+amt/2+")");
			if (rcr.isError()) {
				throw new Exception(rcr.toString());
			}
			
			HashMap<Keyword, Object> config=new HashMap<>();
			config.put(Keywords.KEYPAIR, kp);
			config.put(Keywords.CONTROLLER, a);
			config.put(Keywords.SOURCE, convex);
			Server server=API.launchPeer(config);
			server.getCVMExecutor().setPeer(server.syncPeer(kp,convex)); 
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

	@Override
	public void close() {
		updateRunning=false;
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
		super.close();
	}

	public void addPeer(ConvexLocal cvl) {
		peerList.addElement(cvl);

	}

	@Override
	public void setupFrame(JFrame frame) {
		frame.getContentPane().setLayout(new MigLayout());
		frame.getContentPane().add(this,"dock center");
	}





}