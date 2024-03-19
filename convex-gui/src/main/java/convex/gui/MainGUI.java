package convex.gui;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.event.WindowEvent;

import javax.swing.JFrame;
import javax.swing.JPanel;

import convex.gui.manager.mainpanels.HomePanel;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class MainGUI extends JPanel implements Runnable {
	JFrame frame;
	
	public MainGUI() {
		MigLayout layout=new MigLayout("center");
		setLayout(layout);
		
		add(new HomePanel());
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
