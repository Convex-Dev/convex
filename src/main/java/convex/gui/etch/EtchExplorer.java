package convex.gui.etch;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.util.logging.Logger;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import convex.core.store.Stores;
import convex.gui.etch.panels.DatabasePanel;
import etch.store.EtchStore;

/**
 * A Client application for the Convex Network
 */
@SuppressWarnings("serial")
public class EtchExplorer extends JPanel {

	public static final Logger log = Logger.getLogger(EtchExplorer.class.getName());

	private static JFrame frame;

	public static long maxBlock = 0;

	/**
	 * Launch the application.
	 */
	public static void main(String[] args) {
		// call to set up Look and Feel
		convex.gui.manager.Toolkit.init();

		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				try {
					EtchExplorer.frame = new JFrame();
					frame.setTitle("Etch Explorer");
					frame.setIconImage(Toolkit.getDefaultToolkit()
							.getImage(EtchExplorer.class.getResource("/images/Convex.png")));
					frame.setBounds(100, 100, 1024, 768);
					frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
					EtchExplorer window = new EtchExplorer();
					frame.getContentPane().add(window, BorderLayout.CENTER);
					frame.pack();
					frame.setVisible(true);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	/*
	 * Main component panel
	 */
	JPanel panel = new JPanel();

	EtchStore etch=(EtchStore) Stores.DEFAULT;
	
	DatabasePanel homePanel = new DatabasePanel(this);
	JTabbedPane tabs = new JTabbedPane();
	JPanel mainPanel = new JPanel();

	
	/**
	 * Create the application.
	 */
	public EtchExplorer() {
		
		
		setLayout(new BorderLayout());
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("Database", homePanel);
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

	public EtchStore getStore() {
		return etch;
	}
}
