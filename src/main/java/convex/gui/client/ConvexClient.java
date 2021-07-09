package convex.gui.client;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.EventQueue;
import java.util.logging.Logger;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import convex.api.Convex;
import convex.core.State;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.gui.client.panels.HomePanel;
import convex.gui.components.models.StateModel;
import convex.gui.manager.mainpanels.AboutPanel;
import convex.gui.utils.Toolkit;

/**
 * A Client application for the Convex Network.
 *
 * Doesn't run a Peer. Connects to convex.world.
 */
@SuppressWarnings("serial")
public class ConvexClient extends JPanel {

	private static final Logger log = Logger.getLogger(ConvexClient.class.getName());

	public static final AStore CLIENT_STORE = Stores.getGlobalStore();

	private static JFrame frame;

	private static StateModel<State> latestState = StateModel.create(null);

	public static long maxBlock = 0;

	protected Convex convex=null;

	/**
	 * Launch the application.
	 * @param args Command line argument
	 */
	public static void main(String[] args) {
		log.info("Running Convex Client");
		// call to set up Look and Feel
		Toolkit.init();

		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				try {
					ConvexClient.frame = new JFrame();
					frame.setTitle("Convex Client");
					frame.setIconImage(Toolkit.getImage(ConvexClient.class.getResource("/images/Convex.png")));
					frame.setBounds(100, 100, 1024, 768);
					frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
					ConvexClient window = new ConvexClient();
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

	HomePanel homePanel = new HomePanel();
	AboutPanel aboutPanel = new AboutPanel();
	JTabbedPane tabs = new JTabbedPane();
	JPanel mainPanel = new JPanel();

	/**
	 * Create the application.
	 */
	public ConvexClient() {
		setLayout(new BorderLayout());
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("Home", homePanel);
		tabs.add("About", aboutPanel);

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

	public static State getLatestState() {
		return latestState.getValue();
	}

	public static Component getFrame() {
		return frame;
	}

	public static StateModel<State> getStateModel() {
		return latestState;
	}
}
