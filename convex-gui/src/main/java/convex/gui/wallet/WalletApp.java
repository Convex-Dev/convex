package convex.gui.wallet;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;

import convex.api.Convex;
import convex.gui.components.AbstractGUI;
import convex.gui.components.ConnectPanel;
import convex.gui.components.Toast;
import convex.gui.dlfs.DLFSPanel;
import convex.gui.keys.KeyAccess;
import convex.gui.keys.KeyRingPanel;
import convex.gui.peer.stake.PeerStakePanel;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFileSystem;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class WalletApp extends AbstractGUI {
	/*
	 * Main component panel
	 */
	JPanel panel = new JPanel();

	JTabbedPane tabs = new JTabbedPane(JTabbedPane.LEFT);

	protected Convex convex;

	protected static final int TAB_ICON_SIZE=Toolkit.ICON_SIZE;

	/**
	 * Create the application. The caller keeps ownership of the connection,
	 * and may hand it over with {@link #own(AutoCloseable)}.
	 * @param convex Connection for this wallet
	 */
	public WalletApp(Convex convex) {
		super ("Convex Wallet");
		this.convex=convex;

		setLayout(new MigLayout("fill"));

		add(new AccountOverview(convex),"dock north");

		addTab("Wallet", 0xe850, new TokenListPanel(convex));

		addTab("Friends",0xf233, new FriendPanel(convex));

		DLFileSystem drive=own(DLFS.createLocal());
		DLFSPanel drivePanel=new DLFSPanel(drive);
		own(drivePanel::close);
		addTab("Drive", 0xe1db, drivePanel.createView());

		addTab("Staking", 0xf56e, new PeerStakePanel(convex));

		KeyRingPanel keyPanel=new KeyRingPanel();
		addTab("Keys", 0xe73c, keyPanel);
		
		addTab("QR Code", 0xf206, new QRPanel(convex));
		// addTab("Terminal", SymbolIcon.get(0xeb8e,TAB_ICON_SIZE), new REPLPanel(convex));
		addTab("Settings", 0xe8b8, new SettingsPanel(convex));
		
		this.add(tabs, "dock center");
	}
	
	@Override
	public void afterRun() {
		super.afterRun();
		if (convex.getKeyPair()==null) {
			KeyAccess.signerFor(this, convex).thenAccept(signer->{
				if (signer.keyPair()!=null) {
					convex.setKeyPair(signer.keyPair());
				} else {
					Toolkit.showMessge(this, signer.reason()+"\n\nWallet opened in read-only mode: transactions will fail.");
				}
			});
		}
	}

	private void addTab(String name, int iconCode, JComponent panel) {
		SymbolIcon icon= SymbolIcon.get(iconCode,TAB_ICON_SIZE);
		
		tabs.addTab("", icon, panel);
		
		int i=tabs.getTabCount()-1;
		tabs.setToolTipTextAt(i, name);
		
		//JPanel p=new JPanel();
		//p.setBorder(null);
		//p.setOpaque(false);
		//p.setEnabled(false);
		JLabel label=new JLabel(); 
		label.setHorizontalTextPosition(JLabel.CENTER);
		label.setVerticalTextPosition(JLabel.BOTTOM);
		label.setAlignmentX(JLabel.CENTER);
		label.setToolTipText(name);
		label.setIcon(icon);
		label.setHorizontalAlignment(SwingConstants.CENTER);
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
			WalletApp app=new WalletApp(convex);
			app.own(convex);
			app.run();
			app.waitForClose();
		} 
		Thread.sleep(Toast.DEFAULT_TIME);	
		System.exit(0);
	}
}
