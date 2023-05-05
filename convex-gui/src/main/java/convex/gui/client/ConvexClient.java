package convex.gui.client;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.EventQueue;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.util.Utils;
import convex.gui.client.panels.HomePanel;
import convex.gui.manager.mainpanels.AboutPanel;
import convex.gui.manager.mainpanels.WalletPanel;
import convex.gui.manager.windows.peer.REPLPanel;
import convex.gui.utils.Toolkit;

/**
 * A Client application for the Convex Network.
 *
 * Doesn't run a Peer. Connects to convex.world.
 */
@SuppressWarnings("serial")
public class ConvexClient extends JPanel {

	private static final Logger log = LoggerFactory.getLogger(ConvexClient.class.getName());

	private static JFrame frame;

	public static long maxBlock = 0;
	
	static boolean clientMode=false;

	protected Convex convex=null;

	/**
	 * Launch the application.
	 * @param args Command line argument
	 */
	public static void main(String[] args) {
		log.info("Running Convex Client");
		clientMode=true;
		
		// call to set up Look and Feel
		Toolkit.init();

		EventQueue.invokeLater(()->launch(null));
	}

	/*
	 * Main component panel
	 */
	JPanel panel = new JPanel();

	HomePanel homePanel = new HomePanel();
	AboutPanel aboutPanel = new AboutPanel();
	public JTabbedPane tabs = new JTabbedPane();
	JPanel mainPanel = new JPanel();
	WalletPanel walletPanel=new WalletPanel();
	public REPLPanel replPanel;

	/**
	 * Create the application.
	 * @param convex Convex client instance
	 */
	public ConvexClient(Convex convex) {
		setLayout(new BorderLayout());
		replPanel=new REPLPanel(convex);
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("Home", homePanel);
		tabs.add("About", aboutPanel);
		tabs.add("Wallet", walletPanel);
		tabs.add("REPL", replPanel);
		
		// walletPanel.addWalletEntry(WalletEntry.create(convex.getAddress(), convex.getKeyPair()));
		
		this.convex=convex;

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


	public static Component getFrame() {
		return frame;
	}
	
	public static ConvexClient launch(Convex convex) {
		try {
			ConvexClient.frame = new JFrame();
			frame.setTitle("Convex Client - "+convex);
			frame.setIconImage(Toolkit.getImage(ConvexClient.class.getResource("/images/Convex.png")));
			frame.setBounds(100, 100, 1024, 768);
			if (clientMode) {
				frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			}
			ConvexClient window = new ConvexClient(convex);
			frame.getContentPane().add(window, BorderLayout.CENTER);
			frame.pack();
			frame.setVisible(true);
			return window;
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

}
