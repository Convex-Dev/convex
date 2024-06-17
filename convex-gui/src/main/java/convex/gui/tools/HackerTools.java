package convex.gui.tools;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.util.concurrent.TimeoutException;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.util.Utils;
import convex.dlfs.DLFS;
import convex.gui.components.AbstractGUI;
import convex.gui.dlfs.DLFSPanel;
import convex.gui.keys.KeyGenPanel;
import convex.gui.keys.KeyRingPanel;
import convex.gui.utils.Toolkit;

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
		clientMode=true;
		
		// call to set up Look and Feel
		Toolkit.init();

		EventQueue.invokeLater(()->launch());
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
	
	public static HackerTools launch() {
		try {
			JFrame frame = new JFrame();
			frame.setTitle("Hacker Tools");
			frame.setIconImage(Toolkit.getImage(HackerTools.class.getResource("/images/Convex.png")));
			frame.setBounds(200, 200, 1024, 768);
			if (clientMode) {
				frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			}
			HackerTools window = new HackerTools();
			window.frame=frame;
			frame.getContentPane().add(window, BorderLayout.CENTER);
			frame.pack();
			frame.setVisible(true);
			return window;
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	
	
}
