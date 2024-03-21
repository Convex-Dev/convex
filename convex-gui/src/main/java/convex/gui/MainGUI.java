package convex.gui;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.event.WindowEvent;

import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.JComponent;

import convex.core.crypto.AKeyPair;
import convex.gui.components.ActionPanel;
import convex.gui.manager.mainpanels.HomePanel;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class MainGUI extends JPanel implements Runnable {
	JFrame frame;
	
	public MainGUI() {
		MigLayout layout=new MigLayout("center");
		setLayout(layout);
		
		add(new HomePanel(),"dock center");
		
		ActionPanel actionPanel=new ActionPanel();
		actionPanel.setLayout(new MigLayout("center,align center,fillx"));
		
		JComponent testNet=createLaunchButton("Launch TestNet",Toolkit.TESTNET_ICON,this::launchTestNet);
		actionPanel.add(testNet);
		
		JComponent terminal=createLaunchButton("Convex Terminal",Toolkit.TERMINAL_ICON,this::launchTestNet);
		actionPanel.add(terminal);
		
		JComponent hacker=createLaunchButton("Hacker Tools",Toolkit.HACKER_ICON,this::launchTestNet);
		actionPanel.add(hacker);

		JComponent discord=createLaunchButton("Community Discord",Toolkit.ECOSYSTEM_ICON,this::launchTestNet);
		actionPanel.add(discord);

		JComponent www=createLaunchButton("https://convex.world",Toolkit.WWW_ICON,this::launchTestNet);
		actionPanel.add(www);


		
		add(actionPanel,"dock south");
	}
	
	public void launchTestNet() {
		PeerGUI.launchPeerGUI(3, AKeyPair.generate());
	}
	
	public JPanel createLaunchButton(String label, ImageIcon icon, Runnable cmd) {
		JButton butt=new JButton(icon);
		butt.addActionListener(e->{
			EventQueue.invokeLater(cmd);
		});
		
		JLabel lab = new JLabel(label);
		lab.setHorizontalAlignment(SwingConstants.CENTER);
		
		JPanel panel=new JPanel();
		panel.setLayout(new MigLayout());
		panel.add(butt,"dock center");
		panel.add(lab,"dock south");
		return panel;
	}

	@Override
	public void run() {
		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				try {
					frame = new JFrame();
					frame.setTitle("Convex GUI");
					frame.setIconImage(Toolkit.getDefaultToolkit()
							.getImage(MainGUI.class.getResource("/images/Convex.png")));
					frame.setBounds(100, 100, 1200, 900);
					frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

					frame.getContentPane().add(MainGUI.this, BorderLayout.CENTER);
					frame.setVisible(true);

					frame.addWindowListener(new java.awt.event.WindowAdapter() {
				        public void windowClosing(WindowEvent winEvt) {
				        	// shut down peers gracefully
				        }
				    });

				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}
	
	/**
	 * Launch the application.
	 * @param args Command line args
	 */
	public static void main(String[] args) {
		// call to set up Look and Feel
		Toolkit.init();
		new MainGUI().run();
	}

}
