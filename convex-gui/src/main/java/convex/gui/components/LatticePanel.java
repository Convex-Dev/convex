package convex.gui.components;

import java.awt.EventQueue;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.swing.JPanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.gui.utils.Async;
import convex.gui.utils.UpdateTrigger;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.MissingValueException;

/**
 * A Swing view of one domain component. Subclasses build their widgets normally
 * and render the whole view from one immutable snapshot. Construct on the EDT.
 * Reads run in the background; {@link #render} runs on the EDT only when the
 * snapshot changes. Rendering is never called from this constructor.
 *
 * <p>Showing subscribes before reading; hiding unsubscribes and discards pending
 * delivery. Triggers only request a fresh read, so changing the trigger does not
 * change rendering. Domain operations and publication remain the caller's choice.
 * Read or render failures retain the last successful snapshot and call
 * {@link #onFailure}; a later trigger retries.</p>
 */
@SuppressWarnings("serial")
public abstract class LatticePanel<V extends ACell> extends JPanel implements AutoCloseable {

	private static final Logger log=LoggerFactory.getLogger(LatticePanel.class);

	private final Supplier<V> source;
	private final UpdateTrigger trigger;
	private final AtomicBoolean closed=new AtomicBoolean();
	private final AtomicBoolean queued=new AtomicBoolean();
	private final HierarchyListener hierarchy=e -> {
		if ((e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0) follow();
	};

	// EDT only. Each showing lifetime has a different epoch, invalidating older reads.
	private boolean following,reading,again;
	private long epoch;
	private Runnable unsubscribe;
	private Snapshot<V> shown;

	/** Follows a component using a local read every 200 ms while showing. */
	protected LatticePanel(ALatticeComponent<V> component) {
		this(component,UpdateTrigger.polling(200));
	}

	/** Follows the same component with a caller-selected source of update signals. */
	protected LatticePanel(ALatticeComponent<V> component,UpdateTrigger trigger) {
		this(component::value,trigger);
	}

	/**
	 * Adapts existing models during migration. The supplier must return one immutable
	 * snapshot, be safe on a background thread, and never read Swing state.
	 */
	protected LatticePanel(Supplier<V> source,UpdateTrigger trigger) {
		if (!EventQueue.isDispatchThread()) throw new IllegalStateException("Construct lattice views on the EDT");
		this.source=Objects.requireNonNull(source);
		this.trigger=Objects.requireNonNull(trigger);
		addHierarchyListener(hierarchy);
		EventQueue.invokeLater(this::follow);
	}

	/** Renders the whole view from this snapshot, including stored nil. Runs on the EDT. */
	protected abstract void render(V snapshot);

	/** Handles an absent location. Override to distinguish absence from a stored nil. */
	protected void renderAbsent() { render(null); }

	/** Reports a read, render or trigger failure on the EDT. Subclasses may display a status. */
	protected void onFailure(Throwable failure) { log.warn("Unable to refresh lattice view",failure); }

	/** Requests a read while showing, e.g. after a local command. Safe from any thread. */
	public final void refresh() {
		if (closed.get() || !queued.compareAndSet(false,true)) return;
		EventQueue.invokeLater(() -> {
			queued.set(false);
			if (closed.get() || !following) return;
			if (reading) again=true;
			else read();
		});
	}

	private void read() {
		reading=true;
		again=false;
		long version=epoch;
		Async.background(() -> {
			try { return new Snapshot<>(true,source.get()); }
			catch (MissingValueException e) { return new Snapshot<V>(false,null); }
		}).whenCompleteAsync((snapshot,failure) -> {
			try {
				if (closed.get() || !following || version!=epoch) return;
				if (failure!=null) onFailure(failure);
				else {
					try {
						if (!snapshot.equals(shown)) {
							if (snapshot.present()) render(snapshot.value());
							else renderAbsent();
						}
						// Adopt equal persisted cells too, without rerendering or retaining the old graph.
						shown=snapshot;
					} catch (Exception e) { onFailure(e); }
				}
			} finally {
				reading=false;
				if (again) refresh();
			}
		},Async.EDT);
	}

	private void follow() {
		boolean showing=isShowing() && !closed.get();
		if (following==showing) return;
		following=showing;
		epoch++;
		if (showing) {
			try { unsubscribe=Objects.requireNonNull(trigger.subscribe(this::refresh)); }
			catch (Exception e) { onFailure(e); }
			refresh();
		} else {
			again=false;
			Runnable stop=unsubscribe;
			unsubscribe=null;
			if (stop!=null) {
				try { stop.run(); }
				catch (Exception e) { onFailure(e); }
			}
		}
	}

	/** Permanently stops this view. Safe from any thread; an already running read is not interrupted. */
	@Override public final void close() {
		if (!closed.compareAndSet(false,true)) return;
		Runnable release=() -> {
			follow();
			removeHierarchyListener(hierarchy);
		};
		if (EventQueue.isDispatchThread()) release.run();
		else EventQueue.invokeLater(release);
	}

	private record Snapshot<V>(boolean present,V value) {}
}
