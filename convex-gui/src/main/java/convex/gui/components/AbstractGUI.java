package convex.gui.components;


import java.awt.EventQueue;
import java.awt.event.WindowEvent;

import javax.swing.JFrame;
import javax.swing.JPanel;

import convex.gui.MainGUI;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * Base class for Convex GUI apps
 */
@SuppressWarnings("serial")
public class AbstractGUI extends JPanel implements Runnable {

	protected JFrame frame=null;
	
	@Override
	public void run() {
		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				try {
					frame = new JFrame();
					frame.setTitle(getTitle());
					frame.setIconImage(Toolkit.getDefaultToolkit()
							.getImage(MainGUI.class.getResource("/images/Convex.png")));
					frame.setBounds(50, 50, 1000, 800);
					frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

					frame.getContentPane().setLayout(new MigLayout());
					frame.getContentPane().add(AbstractGUI.this, "dock center");
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
	 * Get the title to be used for this GUI
	 * @return
	 */
	public String getTitle() {
		return "Convex Desktop";
	}

}
