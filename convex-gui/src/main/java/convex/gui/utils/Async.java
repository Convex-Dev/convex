package convex.gui.utils;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.util.ThreadUtils;
import convex.gui.components.Toast;

/**
 * Threading for the GUI. Two rules: blocking work never runs on the event
 * dispatch thread (EDT), and Swing state is only touched on it.
 *
 * <ul>
 * <li>Continue UI work after a future with {@code thenAcceptAsync(fn, Async.EDT)}</li>
 * <li>Run a blocking call off the EDT with {@link #background(Callable)}</li>
 * <li>Start work from a button or menu item with {@link #whileBusy(ActionEvent, CompletableFuture)}</li>
 * </ul>
 */
public final class Async {

	private static final Logger log = LoggerFactory.getLogger(Async.class);

	private Async() {
	}

	/** Runs tasks on the EDT */
	public static final Executor EDT = EventQueue::invokeLater;

	/**
	 * Runs blocking work on a virtual thread
	 * @param <T> Result type
	 * @param work Blocking work, e.g. a synchronous network call
	 * @return Future for the result, completing exceptionally if the work throws
	 */
	public static <T> CompletableFuture<T> background(Callable<T> work) {
		CompletableFuture<T> result=new CompletableFuture<>();
		ThreadUtils.runVirtual("gui-background", ()->{
			try {
				result.complete(work.call());
			} catch (Throwable t) {
				if (t instanceof InterruptedException) Thread.currentThread().interrupt();
				result.completeExceptionally(t);
			}
		});
		return result;
	}

	/**
	 * Disables the button or menu item that started an action until its work
	 * completes, then re-enables it and shows any failure. Call on the EDT.
	 * @param e Action event from the button or menu item
	 * @param work Work started by the action
	 */
	public static void whileBusy(ActionEvent e, CompletableFuture<?> work) {
		Component source=(e.getSource() instanceof Component c)?c:null;
		if (source!=null) source.setEnabled(false);
		work.whenCompleteAsync((r,t)->{
			if (source!=null) source.setEnabled(true);
			if (t!=null) showFailure(source, t);
		}, EDT);
	}

	/**
	 * Shows a failure of background work as a toast. Call on the EDT.
	 * @param near Component the failure relates to, or null
	 * @param t Failure
	 */
	public static void showFailure(Component near, Throwable t) {
		Throwable cause=((t instanceof CompletionException)&&(t.getCause()!=null))?t.getCause():t;
		log.info("GUI action failed",cause);
		JComponent parent=(near==null)?null:SwingUtilities.getRootPane(near);
		Toast.display(parent, "Failed: "+cause.getMessage(), Toast.FAIL);
	}
}
