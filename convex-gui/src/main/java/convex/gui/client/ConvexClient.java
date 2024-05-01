package convex.gui.client;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.util.Utils;
import convex.gui.components.ConnectPanel;
import convex.gui.panels.REPLPanel;
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
	 * @throws TimeoutException 
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException, TimeoutException {
		log.info("Running Convex Client");
		clientMode=true;
		
		// call to set up Look and Feel
		Toolkit.init();

		Convex convex=ConnectPanel.tryConnect(null,"Connect to Convex");
		EventQueue.invokeLater(()->launch(convex));
	}

	public JTabbedPane tabs = new JTabbedPane();
	JPanel mainPanel = new JPanel();
	public REPLPanel replPanel;

	/**
	 * Create the application.
	 * @param convex Convex client instance
	 */
	public ConvexClient(Convex convex) {
		setLayout(new BorderLayout());
		replPanel=new REPLPanel(convex);
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("REPL", replPanel);
		
		// walletPanel.addWalletEntry(WalletEntry.create(convex.getAddress(), convex.getKeyPair()));
		
		this.setPreferredSize(new Dimension(800,600));
		
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
