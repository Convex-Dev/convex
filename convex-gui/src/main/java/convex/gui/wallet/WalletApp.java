package convex.gui.wallet;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import convex.api.Convex;
import convex.gui.components.AbstractGUI;
import convex.gui.components.ConnectPanel;
import convex.gui.keys.KeyRingPanel;
import convex.gui.peer.mainpanels.HomePanel;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

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
		super ("Desktop Wallet");
		this.convex=convex;
		setLayout(new MigLayout());
		this.add(tabs, "dock center");

		tabs.addTab("Wallet", SymbolIcon.get(0xe850), new WalletPanel(convex));
		tabs.addTab("Keys", SymbolIcon.get(0xe73c), new KeyRingPanel());
		tabs.addTab("QR Code", SymbolIcon.get(0xf206), new QRPanel(convex));
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
		Convex convex=ConnectPanel.tryConnect(null,"Connect to Convex");
		if (convex!=null) {
			new WalletApp(convex).run();
		}
	}


}
