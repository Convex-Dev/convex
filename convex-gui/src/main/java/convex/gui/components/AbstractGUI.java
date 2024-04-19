package convex.gui.components;


import java.awt.EventQueue;

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

	protected JFrame frame=new JFrame();
	
	@Override
	public void run() {
		// call to set up Look and Feel
		Toolkit.init();
		
		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				try {
					frame.setTitle(getTitle());
					frame.setIconImage(Toolkit.getDefaultToolkit()
							.getImage(MainGUI.class.getResource("/images/Convex.png")));
					frame.setBounds(50, 50, 1200, 920);
					
					Toolkit.closeIfFirstFrame(frame);


					frame.getContentPane().setLayout(new MigLayout());
					frame.getContentPane().add(AbstractGUI.this, "dock center");
					frame.setVisible(true);


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
	
	public JFrame getFrame() {
		return frame;
	}

}
