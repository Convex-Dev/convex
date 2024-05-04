package convex.gui.components;


import java.awt.BorderLayout;
import java.awt.EventQueue;

import javax.swing.JFrame;
import javax.swing.JPanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.gui.MainGUI;
import convex.gui.utils.Toolkit;

/**
 * Base class for Convex GUI apps
 */
@SuppressWarnings("serial")
public class AbstractGUI extends JPanel implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(AbstractGUI.class.getName());
	
	protected JFrame frame=new JFrame();
	private String title;
	
	public AbstractGUI(String title) {
		this.title=title;
	}
	

	@Override
	public void run() {
		// call to set up Look and Feel
		Toolkit.init();
		
		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				try {
					frame.setTitle(title);
					frame.setIconImage(Toolkit.getDefaultToolkit()
							.getImage(MainGUI.class.getResource("/images/Convex.png")));
					frame.setBounds(50, 50, 1200, 920);
					
					Toolkit.closeIfFirstFrame(frame);

					frame.getContentPane().setLayout(new BorderLayout());
					frame.getContentPane().add(AbstractGUI.this, BorderLayout.CENTER);
					frame.setVisible(true);

				} catch (Exception e) {
					log.warn("GUI launch failed");
					e.printStackTrace();
					// General exit code with error
					System.exit(1);
				}
			}
		});
	}
	
	public JFrame getFrame() {
		return frame;
	}

}
