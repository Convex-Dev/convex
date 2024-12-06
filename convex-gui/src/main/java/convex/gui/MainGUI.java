package convex.gui;

import java.awt.EventQueue;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import convex.api.Convex;
import convex.gui.components.AbstractGUI;
import convex.gui.components.ActionPanel;
import convex.gui.components.ConnectPanel;
import convex.gui.dlfs.DLFSBrowser;
import convex.gui.panels.HomePanel;
import convex.gui.peer.PeerLaunchDialog;
import convex.gui.repl.REPLClient;
import convex.gui.tools.HackerTools;
import convex.gui.utils.Toolkit;
import convex.gui.wallet.WalletApp;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class MainGUI extends AbstractGUI {
	public MainGUI() {
		super ("Convex Desktop");
		MigLayout layout=new MigLayout("wrap 1","[fill]");
		setLayout(layout);
		
		add(new HomePanel(),"dock center");
		
		ActionPanel actionPanel=new ActionPanel();
		actionPanel.setLayout(new MigLayout("center,align center,fillx"));
		
		JComponent wallet=createLaunchButton("Wallet",Toolkit.WALLET_ICON,this::launchWallet,"Open a Convex Wallet, connecting to any existing network");
		actionPanel.add(wallet);

		JComponent testNet=createLaunchButton("Peer Manager",Toolkit.TESTNET_ICON,this::launchTestNet,"Launch a DevNet with the Peer Manager. This gives you a test network you can use freely for testing and development purposes.");
		actionPanel.add(testNet);
		
		JComponent latticeFS=createLaunchButton("Lattice Filesystem",Toolkit.DLFS_ICON,this::launchDLFS,"Launch a DLFS file browser. EXPERIMENTAL.");
		actionPanel.add(latticeFS);

		JComponent terminal=createLaunchButton("Terminal",Toolkit.TERMINAL_ICON,this::launchTerminalClient,"Open a Convex REPL terminal, connecting to any existing network");
		actionPanel.add(terminal);
		
		JComponent hacker=createLaunchButton("Hacker Tools",Toolkit.HACKER_ICON,this::launchTools,"Open a set of useful tools for hackers and power users.");
		actionPanel.add(hacker);

		JComponent discord=createLaunchButton("Discord",Toolkit.ECOSYSTEM_ICON,this::launchDiscord,"Go to the Convex community Discord (opens web browser).");
		actionPanel.add(discord);

		JComponent www=createLaunchButton("Documentation",Toolkit.WWW_ICON,this::launchWebsite,"Go to the Convex docs website (opens web browser).");
		actionPanel.add(www);
		
		add(actionPanel,"dock south");
	}
	
	public void launchDLFS() {
		new DLFSBrowser().run();
	}
	
	public void launchWallet() {
	    Convex convex=ConnectPanel.tryConnect(this);
	    if (convex!=null) {
	    	new WalletApp(convex).run();
	    }
	}
	
	
	public void launchTestNet() {
		PeerLaunchDialog.runLaunchDialog(this);
	}
	
	public void launchDiscord() {
		Toolkit.launchBrowser("https://discord.com/invite/xfYGq4CT7v");
	}
	
	public void launchTerminalClient() {
	    Convex convex=ConnectPanel.tryConnect(this);
	    if (convex!=null) {
	    	new REPLClient(convex).run();
	    }
	}
	
	public HackerTools launchTools() {
		HackerTools tools=new HackerTools();
		tools.run();
		return tools;
	}
	
	public void launchWebsite() {
		Toolkit.launchBrowser("https://docs.convex.world");
	}
	
	public JPanel createLaunchButton(String label, ImageIcon icon, Runnable cmd, String tooltip) {
		JButton butt=new JButton(icon);
		butt.addActionListener(e->{
			EventQueue.invokeLater(cmd);
		});
		butt.setToolTipText(tooltip);
		
		JLabel lab = new JLabel(label);
		lab.setFont(Toolkit.BUTTON_FONT);
		lab.setHorizontalAlignment(SwingConstants.CENTER);
		
		JPanel panel=new JPanel();
		panel.setLayout(new MigLayout("center, wrap 1","[align center]"));
		panel.add(butt);
		panel.add(lab);
		return panel;
	}

	/**
	 * Launch the Convex Desktop application and waits until main frame is closed.
	 * @param args Command line args
	 */
	public static void main(String... args) {
		MainGUI gui=new MainGUI();
		gui.run();
		gui.waitForClose();
		System.exit(0);
	}

	@Override
	public void setupFrame(JFrame frame) {
		frame.getContentPane().setLayout(new MigLayout());
		frame.getContentPane().add(this,"dock center");
	}

}
