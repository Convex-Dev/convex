package convex.gui.components;


import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.util.ThreadUtils;
import convex.core.util.Utils;
import convex.gui.MainGUI;
import convex.gui.utils.Toolkit;
import convex.gui.utils.TrayManager;
import net.miginfocom.swing.MigLayout;

/**
 * Base class for Convex GUI apps: a panel shown in its own frame.
 *
 * <p>Closing the frame, {@link #closeGUI()} and {@link #close()} all run the
 * same teardown, exactly once. The frame is disposed on the event dispatch
 * thread, then resources are released on a background thread: first every
 * component in this GUI that implements {@link AutoCloseable}, then every
 * resource registered with {@link #own(AutoCloseable)}, most recent first.
 * {@link #waitForClose()} returns once all of that has finished.
 *
 * <p>Release runs off the event dispatch thread, after the frame has gone, so
 * a component's {@code close()} should only free resources (stop timers and
 * threads, remove listeners) and never touch Swing state.
 */
@SuppressWarnings("serial")
public abstract class AbstractGUI extends JPanel implements Runnable, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(AbstractGUI.class.getName());


	protected JFrame frame;
	private String title;

	public AbstractGUI(String title) {
		this.title=title;
	}

	static {
		Toolkit.init();
	}

	/** Set once teardown has been requested */
	private final AtomicBoolean closing=new AtomicBoolean();

	/** Resources released on close, in reverse order. Guarded by itself. */
	private final ArrayList<AutoCloseable> owned=new ArrayList<>();

	/** Set once owned resources have been taken for release. Guarded by owned. */
	private boolean drained=false;

	/**
	 * Future that is completed when this GUI has closed and released its resources
	 */
	private final CompletableFuture<Void> closed=new CompletableFuture<>();

	/**
	 * Runs this GUI component asynchronously in a new Frame
	 */
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

	/**
	 * Runs this GUI component new Frame, positioned relative to a given parent
	 */
	public void runNonModal(JComponent parent) {
		showFrame();
		if ((parent!=null)&&(frame!=null)) {
			Rectangle b=parent.getBounds();
			frame.setLocation(b.x+100, b.y+100);
		}
	}

	public void showFrame() {
		if (closing.get()) return;
		frame=new JFrame();
		frame.setTitle(title);
		frame.setIconImage(Toolkit.getDefaultToolkit()
				.getImage(MainGUI.class.getResource("/images/Convex.png")));
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

		frame.addWindowListener(new WindowAdapter() {
			@Override
	        public void windowClosed(WindowEvent e) {
	            closeGUI();
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
		if (TrayManager.install(title,"Open GUI",this::openGUI,this::closeGUI)) {
			own(TrayManager::remove);
		}
	}

	private void openGUI() {
		EventQueue.invokeLater(()->{
			JFrame current=frame;
			if (current==null) return;
			current.setVisible(true);
			current.setExtendedState(current.getExtendedState()&~JFrame.ICONIFIED);
			current.toFront();
			current.requestFocus();
		});
	}

	/**
	 * Registers a resource to be released when this GUI closes. Resources are
	 * released most recent first, after the GUI's own components. If the GUI
	 * has already closed, the resource is released immediately.
	 * @param <T> Type of resource
	 * @param resource Resource to release on close (may be null)
	 * @return The resource, for convenience
	 */
	public <T extends AutoCloseable> T own(T resource) {
		if (resource==null) return null;
		synchronized (owned) {
			if (!drained) {
				owned.add(resource);
				return resource;
			}
		}
		release(resource);
		return resource;
	}

	/**
	 * Removes a resource previously registered with {@link #own(AutoCloseable)},
	 * so it is no longer released when this GUI closes.
	 * @param resource Resource to remove
	 * @return true if the resource was registered
	 */
	public boolean disown(AutoCloseable resource) {
		synchronized (owned) {
			for (int i=owned.size()-1; i>=0; i--) {
				if (owned.get(i)==resource) {
					owned.remove(i);
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Takes ownership of a child GUI: it is closed when this GUI closes, and
	 * released from ownership if it closes first.
	 * @param <T> Type of child GUI
	 * @param child Child GUI to adopt
	 * @return The child, for convenience
	 */
	public <T extends AbstractGUI> T adopt(T child) {
		AbstractGUI gui=child;
		own(gui);
		gui.closed.thenRun(()->disown(gui));
		return child;
	}

	/**
	 * Waits until this GUI has closed and released its resources. Must not be
	 * called on the event dispatch thread, which teardown needs.
	 */
	public void waitForClose() {
		checkNotDispatchThread();
		closed.join();
	}

	/**
	 * @return true once teardown of this GUI has been requested
	 */
	public boolean isClosing() {
		return closing.get();
	}

	public JFrame getFrame() {
		return frame;
	}

	/**
	 * Closes this GUI and waits until its resources are released. Must not be
	 * called on the event dispatch thread: use {@link #closeGUI()} there.
	 */
	@Override
	public final void close() {
		checkNotDispatchThread();
		closeGUI();
		closed.join();
	}

	/**
	 * Requests that this GUI closes, without waiting. Safe from any thread and
	 * safe to call repeatedly.
	 */
	public void closeGUI() {
		if (!closing.compareAndSet(false, true)) return;
		if (EventQueue.isDispatchThread()) {
			beginClose();
		} else {
			EventQueue.invokeLater(this::beginClose);
		}
	}

	/** Disposes the frame and gathers closeable components. Runs on the EDT. */
	private void beginClose() {
		List<AutoCloseable> components=new ArrayList<>();
		try {
			JFrame f=frame;
			if (f!=null) f.dispose();
			collectCloseables(this,components);
		} finally {
			ThreadUtils.runVirtual("gui-close", ()->releaseAll(components));
		}
	}

	/**
	 * Gathers closeable components, children before parents. A nested GUI is
	 * gathered whole, since it releases its own components.
	 */
	private void collectCloseables(Component c, List<AutoCloseable> out) {
		if ((c!=this)&&(c instanceof AbstractGUI)) {
			out.add((AbstractGUI)c);
			return;
		}
		if (c instanceof Container) {
			for (Component child: ((Container)c).getComponents()) {
				collectCloseables(child,out);
			}
		}
		if ((c!=this)&&(c instanceof AutoCloseable)) {
			out.add((AutoCloseable)c);
		}
	}

	private void releaseAll(List<AutoCloseable> components) {
		try {
			for (AutoCloseable c: components) {
				release(c);
			}
			List<AutoCloseable> resources;
			synchronized (owned) {
				drained=true;
				resources=new ArrayList<>(owned);
				owned.clear();
			}
			for (int i=resources.size()-1; i>=0; i--) {
				release(resources.get(i));
			}
			log.debug("GUI closed: "+Utils.getClassName(this));
		} finally {
			closed.complete(null);
		}
	}

	private static void release(AutoCloseable resource) {
		if ((resource instanceof AbstractGUI gui) && EventQueue.isDispatchThread()) {
			// Can't wait for a GUI to close on the EDT, so just request it
			gui.closeGUI();
			return;
		}
		try {
			resource.close();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Interrupted releasing {}",resource);
		} catch (Exception e) {
			log.warn("Failed to release {}",resource,e);
		}
	}

	private static void checkNotDispatchThread() {
		if (EventQueue.isDispatchThread()) {
			throw new IllegalStateException("Cannot wait for GUI close on the event dispatch thread");
		}
	}

}
