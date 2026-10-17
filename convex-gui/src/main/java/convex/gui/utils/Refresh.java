package convex.gui.utils;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.event.HierarchyEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.util.ThreadUtils;

/**
 * Keeps a component up to date by running a task at a fixed interval while the
 * component is showing. Refreshing stops when the component is hidden, e.g. in
 * another tab or a closed window, and starts again, straight away, when it shows.
 *
 * <p>Tasks run off the EDT, so they may block on the network; they hand any
 * results to Swing safely themselves.
 */
public final class Refresh {

	private static final Logger log = LoggerFactory.getLogger(Refresh.class);

	/** Schedules refreshes; the tasks themselves run on virtual threads */
	private static final ScheduledExecutorService SCHEDULER=Executors.newSingleThreadScheduledExecutor(r->{
		Thread t=new Thread(r, "gui-refresh");
		t.setDaemon(true);
		return t;
	});

	/** A refresh task, which may block or throw */
	@FunctionalInterface
	public interface Task {
		void run() throws Exception;
	}

	private final long millis;
	private final Task task;

	/** Set while a run is in progress, so runs never overlap */
	private final AtomicBoolean running=new AtomicBoolean();

	/** Scheduled refreshes while showing, else null. EDT only. */
	private ScheduledFuture<?> scheduled;

	private Refresh(long millis, Task task) {
		this.millis=millis;
		this.task=task;
	}

	/**
	 * Runs a task every {@code millis} milliseconds while a component is showing
	 * @param component Component being kept up to date
	 * @param millis Interval between runs
	 * @param task Task that refreshes the component
	 * @return The refresh, which needs no further handling
	 */
	public static Refresh whileShowing(Component component, long millis, Task task) {
		Refresh refresh=new Refresh(millis, task);
		component.addHierarchyListener(e->{
			if ((e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0) {
				refresh.showing(component.isShowing());
			}
		});
		if (component.isShowing()) EventQueue.invokeLater(()->refresh.showing(true));
		return refresh;
	}

	/** Starts or stops refreshing as the component shows or hides. EDT only. */
	void showing(boolean showing) {
		if (showing==isActive()) return;
		if (showing) {
			scheduled=SCHEDULER.scheduleWithFixedDelay(this::runOnce, 0, millis, TimeUnit.MILLISECONDS);
		} else {
			scheduled.cancel(false);
			scheduled=null;
		}
	}

	/** @return true while refreshing. EDT only. */
	boolean isActive() {
		return scheduled!=null;
	}

	/** Runs the task on a virtual thread, unless the last run is still going */
	private void runOnce() {
		if (!running.compareAndSet(false, true)) return;
		ThreadUtils.runVirtual("gui-refresh-task", ()->{
			try {
				task.run();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				log.debug("Refresh failed",e);
			} finally {
				running.set(false);
			}
		});
	}
}
