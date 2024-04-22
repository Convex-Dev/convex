package convex.gui.wallet;

import java.awt.BorderLayout;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import convex.api.Convex;
import convex.gui.components.AbstractGUI;
import convex.gui.components.ConnectPanel;
import convex.gui.components.QRPanel;
import convex.gui.keys.KeyListPanel;
import convex.gui.peer.mainpanels.HomePanel;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class WalletApp extends AbstractGUI {
	/*
	 * Main component panel
	 */
	JPanel panel = new JPanel();

	HomePanel homePanel = new HomePanel();
	JTabbedPane tabs = new JTabbedPane();

	protected Convex convex;
	
	/**
	 * Create the application.
	 */
	public WalletApp(Convex convex) {
		this.convex=convex;
		setLayout(new BorderLayout());
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("Home", homePanel);
		tabs.add("Keys", new KeyListPanel(convex));
		tabs.add("QR Links", new QRPanel("Test QR code with a reasonable length string to see what happens",300));
	}

	// private static final Logger log = LoggerFactory.getLogger(Wallet.class.getName());
	
	public String getTitle() {
		return "Convex Wallet";
	}

	/**
	 * Launch the application.
	 * @param args Command line args
	 */
	public static void main(String[] args) {
		// call to set up Look and Feel
		Toolkit.init();
		Convex convex=ConnectPanel.tryConnect(null,"Connect Wallet");
		if (convex!=null) {
			new WalletApp(convex).run();
		}
	}


}
