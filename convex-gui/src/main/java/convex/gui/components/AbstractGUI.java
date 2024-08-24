package convex.gui.components;


import java.awt.EventQueue;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.swing.JFrame;
import javax.swing.JPanel;

import convex.gui.MainGUI;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * Base class for Convex GUI apps
 */
@SuppressWarnings("serial")
public abstract class AbstractGUI extends JPanel implements Runnable {
	
	protected JFrame frame=new JFrame();
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
				frame.setTitle(title);
				frame.setIconImage(Toolkit.getDefaultToolkit()
						.getImage(MainGUI.class.getResource("/images/Convex.png")));
				
			
				frame.addWindowListener(new WindowAdapter() {
					@Override
			        public void windowClosing(WindowEvent e) {
			            finished.complete("Closed");
			        }
				});

				setupFrame(frame);
				frame.pack();
				frame.setVisible(true);
			}
		});
	}
	
	/**
	 * Implementations should override this to add the gui components and configure the GUI frame
	 * @param frame
	 */
	public void setupFrame(JFrame frame) {
		frame.getContentPane().setLayout(new MigLayout());
		frame.getContentPane().add(this,"dock center");
	}
	
	public void waitForClose() {
		try {
			finished.get();
			close();
		} catch (ExecutionException e) {
			// Probably won't happen?
		} catch (InterruptedException e) {
			// Set interrupt status, in case caller wants to handle this
			Thread.currentThread().interrupt();
		}
	}
	
	public JFrame getFrame() {
		return frame;
	}

	public void close() {
		// nothing to do
	}
	
	
	public void closeGUI() {
		frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
		close();
	}

}
