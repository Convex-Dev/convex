package convex.gui.components;


import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.util.Utils;
import convex.gui.MainGUI;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * Base class for Convex GUI apps
 */
@SuppressWarnings("serial")
public abstract class AbstractGUI extends JPanel implements Runnable {
	
	private static final Logger log = LoggerFactory.getLogger(AbstractGUI.class.getName());

	
	protected JFrame frame;
	private String title;
	
	public AbstractGUI(String title) {
		this.title=title;
	}
	
	static {
		Toolkit.init();
	}
	
	private CompletableFuture<String> finished=new CompletableFuture<>();

	@Override
	public final void run() {
		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				try {
					showFrame();
					EventQueue.invokeLater(AbstractGUI.this::afterRun);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}
	
	public void runNonModal(JComponent parent) {
		showFrame();
		if (parent!=null) {
			Rectangle b=parent.getBounds();
			frame.setLocation(b.x+100, b.y+100);
		}
		
	}
	
	public void showFrame() {
		frame=new JFrame();
		frame.setTitle(title);
		frame.setIconImage(Toolkit.getDefaultToolkit()
				.getImage(MainGUI.class.getResource("/images/Convex.png")));
		
		frame.addWindowListener(new WindowAdapter() {
			@Override
	        public void windowClosing(WindowEvent e) {
	            finished.completeAsync(()->"Closed");
	        }
		});

		setupFrame(frame);
		frame.pack();
		frame.setVisible(true);
		log.info("GUI displayed: "+Utils.getClassName(this));
	}

	/**
	 * Runs this GUI element until it is closed
	 */
	public void runUntilClosed() {
		run();
		waitForClose();
	}
	
	/**
	 * Implementations should override this to add the gui components and configure the GUI frame
	 * @param frame Frame in which to set up this GUI screen
	 */
	public void setupFrame(JFrame frame) {
		frame.getContentPane().setLayout(new MigLayout());
		frame.getContentPane().add(this,"dock center");
	}
	
	/**
	 * Called after the GUI interface is run
	 */
	public void afterRun() {
		
	}
	
	public void waitForClose() {
		if (finished!=null) {
			finished.join();
		}
		close();
	}
	
	public JFrame getFrame() {
		return frame;
	}

	public void close() {
		// nothing to do by default
	}
	
	
	public void closeGUI() {
		frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
		close();
	}

}
