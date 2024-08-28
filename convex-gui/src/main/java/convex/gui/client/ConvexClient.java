package convex.gui.client;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.gui.components.AbstractGUI;
import convex.gui.components.ConnectPanel;
import convex.gui.keys.KeyRingPanel;
import convex.gui.panels.REPLPanel;
import net.miginfocom.swing.MigLayout;

/**
 * A Client application for the Convex Network.
 *
 * Doesn't run a Peer. Connects to convex.world.
 */
@SuppressWarnings("serial")
public class ConvexClient extends AbstractGUI {

	private static final Logger log = LoggerFactory.getLogger(ConvexClient.class.getName());

	public static long maxBlock = 0;

	protected Convex convex=null;

	/**
	 * Launch the application.
	 * @param args Command line argument
	 * @throws TimeoutException  In case of timeout
	 * @throws IOException In case of connection error
	 */
	public static void main(String[] args) throws IOException, TimeoutException {
		log.info("Starting Convex Client");

		Convex convex=ConnectPanel.tryConnect(null,"Connect to Convex");
		if (convex==null) {
			System.exit(1);
		}
		ConvexClient gui=new ConvexClient(convex);
		gui.run();
		gui.waitForClose();
		System.exit(0);
	}

	public JTabbedPane tabs = new JTabbedPane();
	JPanel mainPanel = new JPanel();
	public REPLPanel replPanel;

	/**
	 * Create the application.
	 * @param convex Convex client instance
	 */
	public ConvexClient(Convex convex) {
		super ("Convex Client");
		setLayout(new BorderLayout());
		replPanel=new REPLPanel(convex);
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("REPL", replPanel);
		tabs.add("KeyRing", new KeyRingPanel());
		
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

	@Override
	public void setupFrame(JFrame frame) {
		frame.getContentPane().setLayout(new MigLayout());
		frame.getContentPane().add(this,"dock center");
	}


}
