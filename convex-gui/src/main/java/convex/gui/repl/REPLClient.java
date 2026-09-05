package convex.gui.repl;

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
import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.gui.components.AbstractGUI;
import convex.gui.components.ConnectPanel;
import convex.gui.keys.KeyRingPanel;
import convex.gui.keys.UnlockWalletDialog;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * A Client application for the Convex Network.
 *
 * Doesn't run a Peer. Connects to convex.world.
 */
@SuppressWarnings("serial")
public class REPLClient extends AbstractGUI {

	private static final Logger log = LoggerFactory.getLogger(REPLClient.class.getName());

	public static long maxBlock = 0;

	protected Convex convex=null;
	private final boolean ownsConnection;

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
		REPLClient gui=new REPLClient(convex,true);
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
	public REPLClient(Convex convex) {
		this(convex,false);
	}

	/**
	 * Creates a terminal with explicit connection ownership.
	 * @param convex client used by the terminal
	 * @param ownsConnection true to close the client with this terminal
	 */
	public REPLClient(Convex convex,boolean ownsConnection) {
		super ("Convex Terminal");
		setLayout(new BorderLayout());
		replPanel=new REPLPanel(convex);
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("REPL", replPanel);
		tabs.add("KeyRing", new KeyRingPanel());
		
		// walletPanel.addWalletEntry(WalletEntry.create(convex.getAddress(), convex.getKeyPair()));
		
		this.setPreferredSize(new Dimension(800,600));
		
		this.convex=convex;
		this.ownsConnection=ownsConnection;
	}
	
	@Override
	public void afterRun() {
		super.afterRun();
		if (convex.getKeyPair()==null) {
			AWalletEntry we=KeyRingPanel.findWalletEntry(convex);
			if (we!=null) {
				if (we.isLocked()) {
					UnlockWalletDialog.offerUnlock(this, we);
				}
				AKeyPair kp=we.getKeyPair();
				convex.setKeyPair(kp);
			} else {
				Toolkit.showMessge(this, "The key for this account is not in your key ring.\n\nTerminal opened in Query mode.");
			}
		}
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

	@Override
	public void close() {
		try {
			if (ownsConnection && convex!=null) convex.close();
		} finally {
			super.close();
		}
	}


}
