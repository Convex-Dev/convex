package convex.gui;

import static org.junit.jupiter.api.Assertions.fail;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.RepaintManager;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails a GUI test if Swing state was changed off the event dispatch thread.
 *
 * <p>Installs a RepaintManager that records repaints and revalidations of
 * showing components requested from other threads by Swing code, i.e. a Swing
 * method was called off the EDT. Calling {@code repaint()} directly is legal
 * from any thread and is not recorded. Active only with a display, and applied
 * to every convex-gui test by JUnit extension auto-detection.
 */
public final class SwingThreadCheck implements BeforeAllCallback, AfterEachCallback {

	private static final List<String> violations=new ArrayList<>();
	private static boolean installed=false;

	@Override
	public void beforeAll(ExtensionContext context) throws Exception {
		install();
	}

	@Override
	public void afterEach(ExtensionContext context) {
		List<String> found=takeViolations();
		if (!found.isEmpty()) {
			fail(found.size()+" Swing call(s) off the event dispatch thread, first:\n"+found.get(0));
		}
	}

	/** Installs the check, once, if there is a display */
	static synchronized void install() throws Exception {
		if (installed || GraphicsEnvironment.isHeadless()) return;
		EventQueue.invokeAndWait(()->RepaintManager.setCurrentManager(new CheckingRepaintManager()));
		installed=true;
	}

	/** @return true once the check is installed */
	static synchronized boolean isInstalled() {
		return installed;
	}

	/**
	 * Takes the violations recorded so far
	 * @return Descriptions with stack traces, oldest first
	 */
	static List<String> takeViolations() {
		synchronized (violations) {
			List<String> found=new ArrayList<>(violations);
			violations.clear();
			return found;
		}
	}

	@SuppressWarnings("serial")
	private static final class CheckingRepaintManager extends RepaintManager {
		@Override
		public synchronized void addInvalidComponent(JComponent component) {
			check(component);
			super.addInvalidComponent(component);
		}

		@Override
		public void addDirtyRegion(JComponent component, int x, int y, int w, int h) {
			check(component);
			super.addDirtyRegion(component, x, y, w, h);
		}

		private static void check(JComponent c) {
			if (EventQueue.isDispatchThread() || !c.isShowing()) return;
			StackTraceElement[] stack=Thread.currentThread().getStackTrace();
			boolean repaint=false;
			boolean fromSwing=false;
			for (StackTraceElement st: stack) {
				String cls=st.getClassName();
				if ("imageUpdate".equals(st.getMethodName())) return; // image loading, allowed
				if (repaint && cls.startsWith("javax.swing.") && !cls.startsWith("javax.swing.SwingWorker")) fromSwing=true;
				if ("repaint".equals(st.getMethodName())) {
					repaint=true;
					fromSwing=false;
				}
			}
			// repaint() called directly by our code is allowed from any thread
			if (repaint && !fromSwing) return;

			StringBuilder sb=new StringBuilder(c.getClass().getName()).append(" on ").append(Thread.currentThread().getName());
			for (StackTraceElement st: stack) sb.append("\n\tat ").append(st);
			synchronized (violations) {
				violations.add(sb.toString());
			}
		}
	}
}
