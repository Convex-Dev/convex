package convex.gui.wallet;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;

import convex.api.Convex;
import convex.core.crypto.wallet.AWalletEntry;
import convex.dlfs.DLFS;
import convex.gui.components.AbstractGUI;
import convex.gui.components.ConnectPanel;
import convex.gui.components.Toast;
import convex.gui.dlfs.DLFSPanel;
import convex.gui.keys.KeyRingPanel;
import convex.gui.panels.HomePanel;
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
	JTabbedPane tabs = new JTabbedPane(JTabbedPane.LEFT);

	protected Convex convex;
	
	protected static final int TAB_ICON_SIZE=Toolkit.ICON_SIZE;
	
	/**
	 * Create the application.
	 */
	public WalletApp(Convex convex) {
		super ("Convex Wallet");
		this.convex=convex;
		
		setLayout(new MigLayout("fill"));
		
		add(new AccountOverview(convex),"dock north");

		addTab("Wallet", SymbolIcon.get(0xe850,TAB_ICON_SIZE), new TokenListPanel(convex));

		addTab("Friends", SymbolIcon.get(0xf233,TAB_ICON_SIZE), new FriendPanel(convex));
		
		addTab("Drive", SymbolIcon.get(0xe1db,TAB_ICON_SIZE), new DLFSPanel(DLFS.createLocal()));


		KeyRingPanel keyPanel=new KeyRingPanel();
		keyPanel.setBorder(Toolkit.createDialogBorder());
		addTab("Keys", SymbolIcon.get(0xe73c,TAB_ICON_SIZE), keyPanel);
		
		addTab("QR Code", SymbolIcon.get(0xf206,TAB_ICON_SIZE), new QRPanel(convex));
		// addTab("Terminal", SymbolIcon.get(0xeb8e,TAB_ICON_SIZE), new REPLPanel(convex));
		addTab("Settings", SymbolIcon.get(0xe8b8,TAB_ICON_SIZE), new SettingsPanel(convex));
		
		this.add(tabs, "dock center");
	}
	
	@Override
	public void afterRun() {
		if (convex.getKeyPair()==null) {
			AWalletEntry we=KeyRingPanel.findWalletEntry(convex);
			if (we!=null) {
				convex.setKeyPair(we.getKeyPair());
			}
		}
	}

	private void addTab(String name, SymbolIcon icon, JComponent panel) {
		tabs.addTab("", icon, panel);
		
		int i=tabs.getTabCount()-1;
		tabs.setToolTipTextAt(i, name);
		
		//JPanel p=new JPanel();
		//p.setBorder(null);
		//p.setOpaque(false);
		//p.setEnabled(false);
		JLabel label=new JLabel(); 
		label.setHorizontalTextPosition(JLabel.CENTER);
		label.setIconTextGap(0);
		label.setAlignmentX(JLabel.CENTER);
		label.setVerticalTextPosition(JLabel.BOTTOM);
		label.setToolTipText(name);
		label.setIcon(icon);
		label.setHorizontalAlignment(SwingConstants.LEFT);
		//p.add(label);
		// tabs.setTabComponentAt(tabs.getTabCount()-1, label);
	}

	// private static final Logger log = LoggerFactory.getLogger(Wallet.class.getName());
	
	public String getTitle() {
		return "Convex Wallet";
	}

	/**
	 * Launch the application.
	 * @param args Command line args
	 * @throws InterruptedException In case of interrupt
	 */
	public static void main(String[] args) throws InterruptedException {
		// call to set up Look and Feel
		Toolkit.init();
		Convex convex=ConnectPanel.tryConnect(null,"Connect to Convex");
		if (convex!=null) {
			new WalletApp(convex).run();
		} else {
			// Quit after waiting long enough to see error message
			Thread.sleep(Toast.DEFAULT_TIME);
			System.exit(0);
		}
	}


}
