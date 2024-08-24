package convex.gui.tools;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.concurrent.TimeoutException;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.dlfs.DLFS;
import convex.gui.components.AbstractGUI;
import convex.gui.dlfs.DLFSPanel;
import convex.gui.keys.KeyGenPanel;
import convex.gui.keys.KeyRingPanel;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * A Client application for the Convex Network.
 *
 * Doesn't run a Peer. Connects to convex.world.
 */
@SuppressWarnings("serial")
public class HackerTools extends AbstractGUI {

	private static final Logger log = LoggerFactory.getLogger(HackerTools.class.getName());
	
	static boolean clientMode=false;


	/**
	 * Launch the application.
	 * @param args Command line argument
	 * @throws TimeoutException In case of timeout
	 */
	public static void main(String[] args) throws TimeoutException {
		log.info("Running Convex HackerTools");
		
		// call to set up Look and Feel
		Toolkit.init();

		HackerTools gui=new HackerTools();
		gui.run();
		gui.waitForClose();
		System.exit(0);
	}

	public JTabbedPane tabs = new JTabbedPane();
	JPanel mainPanel = new JPanel();

	private KeyGenPanel keyGenPanel;

	private MessageFormatPanel messagePanel;
	
	private DLFSPanel dataPanel=new DLFSPanel(DLFS.createLocal());

	/**
	 * Create the application.
	 */
	public HackerTools() {
		super ("Hacker Tools");
		setLayout(new BorderLayout());
		keyGenPanel = new KeyGenPanel(null);
		messagePanel = new MessageFormatPanel();
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("KeyGen", keyGenPanel);
		tabs.add("KeyRing", new KeyRingPanel());
		tabs.add("Encoding", messagePanel);
		tabs.add("Data Lattice", dataPanel);
		
		// walletPanel.addWalletEntry(WalletEntry.create(convex.getAddress(), convex.getKeyPair()));
		
		this.setPreferredSize(new Dimension(1000,800));
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
		frame.setBounds(200, 200, 1024, 768);
	}
	

	
	
}
