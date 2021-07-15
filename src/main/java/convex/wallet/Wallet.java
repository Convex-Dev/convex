package convex.wallet;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.Toolkit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import convex.gui.manager.mainpanels.HomePanel;

@SuppressWarnings("serial")
public class Wallet extends JPanel {

	private static final Logger log = LoggerFactory.getLogger(Wallet.class.getName());

	private static JFrame frame;

	public static long maxBlock = 0;

	/**
	 * Launch the application.
	 * @param args Command line args
	 */
	public static void main(String[] args) {
		// call to set up Look and Feel
		convex.gui.utils.Toolkit.init();

		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				try {
					Wallet.frame = new JFrame();
					frame.setTitle("Convex Secure Wallet");
					frame.setIconImage(Toolkit.getDefaultToolkit()
							.getImage(Wallet.class.getResource("/images/ic_stars_black_36dp.png")));
					frame.setBounds(100, 100, 1024, 768);
					frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
					Wallet window = new Wallet();
					frame.getContentPane().add(window, BorderLayout.CENTER);
					frame.pack();
					frame.setVisible(true);
					log.debug("Wallet GUI launched");
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
	JTabbedPane tabs = new JTabbedPane();
	
	/**
	 * Create the application.
	 */
	public Wallet() {
		setLayout(new BorderLayout());
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("Home", homePanel);

	}
}
