package convex.gui.binding;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.JLabel;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.gui.models.StateModel;
import convex.gui.utils.Async;

/**
 * Display bindings owned by one Swing view. Create and register on the EDT.
 * Suppliers read domain state off the EDT; they must be thread-safe and must not
 * read Swing state. Only changed results are applied, on the EDT.
 *
 * <p>Bindings load once on registration, then follow the owner while it is showing.
 * Hiding unsubscribes or stops sampling; showing reads fresh state. All bindings
 * share one refresh, with at most one background read and one delivery outstanding.
 * Each supplier must obtain its own consistent snapshot: separate suppliers need
 * not see the same version. This is a display of current state, not an event log.
 * No domain edits, publication, persistence or network acquisition are performed.</p>
 */
public final class Bindings implements AutoCloseable {

	private static final Logger log=LoggerFactory.getLogger(Bindings.class);

	private final Component owner;
	private final StateModel<?> updates;
	private final Timer timer;
	private final HierarchyListener hierarchy;
	private final PropertyChangeListener listener=e -> refresh();
	private final List<Binding<?>> bindings=new ArrayList<>();
	private final AtomicBoolean closed=new AtomicBoolean();

	// EDT only. An epoch change discards a read begun before the view was hidden.
	private boolean following;
	private boolean running;
	private boolean again;
	private long epoch;

	private Bindings(Component owner,StateModel<?> updates) {
		checkEDT();
		this.owner=Objects.requireNonNull(owner);
		this.updates=updates;
		timer=new Timer(200,e -> refresh());
		hierarchy=e -> {
			if ((e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0) follow();
		};
		owner.addHierarchyListener(hierarchy);
		follow();
	}

	/** Samples local domain getters every 200 ms while the owner is showing. */
	public static Bindings on(Component owner) {
		return new Bindings(owner,null);
	}

	/** Uses an existing model's change events instead of polling, for incremental migration. */
	public static Bindings on(Component owner,StateModel<?> updates) {
		return new Bindings(owner,Objects.requireNonNull(updates));
	}

	/** Displays a value as text; null displays as an empty string. */
	public void text(JLabel label,Supplier<?> value) {
		Objects.requireNonNull(value);
		add(() -> Objects.toString(value.get(),""),label::setText);
	}

	/** Displays text without resetting the document or selection for an unchanged result. */
	public void text(JTextComponent text,Supplier<?> value) {
		Objects.requireNonNull(value);
		add(() -> Objects.toString(value.get(),""),text::setText);
	}

	/** Enables a component exactly when the supplied value is true. */
	public void enabled(Component component,Supplier<Boolean> value) {
		Objects.requireNonNull(value);
		add(() -> Boolean.TRUE.equals(value.get()),component::setEnabled);
	}

	private <T> void add(Supplier<T> read,Consumer<T> display) {
		checkEDT();
		if (closed.get()) throw new IllegalStateException("Bindings are closed");
		bindings.add(new Binding<>(read,display));
		refresh();
	}

	/** Requests a fresh read, e.g. after a local command. Call on the EDT. Bursts coalesce. */
	public void refresh() {
		checkEDT();
		if (closed.get()) return;
		if (running) {
			again=true;
			return;
		}
		running=true;
		// Defer the read so registrations in the same UI turn form one batch.
		long version=epoch;
		EventQueue.invokeLater(() -> sample(version));
	}

	private void sample(long version) {
		if (closed.get() || version!=epoch) {
			running=false;
			if (again) refresh();
			return;
		}
		again=false;
		var batch=List.copyOf(bindings);
		Async.background(() -> {
			List<Runnable> displays=new ArrayList<>(batch.size());
			for (var binding:batch) {
				if (closed.get()) break;
				try {
					displays.add(binding.sample());
				} catch (Exception e) {
					log.debug("Unable to read bound value",e);
				}
			}
			return displays;
		}).whenCompleteAsync((displays,failure) -> {
			try {
				if (!closed.get() && version==epoch) {
					if (failure!=null) log.warn("Unable to refresh bindings",failure);
					else for (var display:displays) {
						if (closed.get() || version!=epoch) break;
						try {
							display.run();
						} catch (Exception e) {
							log.warn("Unable to display bound value",e);
						}
					}
				}
			} finally {
				running=false;
				if (again) refresh();
			}
		},Async.EDT);
	}

	private void follow() {
		boolean showing=owner.isShowing() && !closed.get();
		if (following==showing) return;
		following=showing;
		epoch++;
		if (showing) {
			if (updates==null) timer.start();
			else updates.addPropertyChangeListener(listener);
			refresh();
		} else {
			timer.stop();
			if (updates!=null) updates.removePropertyChangeListener(listener);
			again=false;
		}
	}

	/**
	 * Permanently stops following the owner. Safe from any thread; queued deliveries
	 * are suppressed immediately and listener removal is completed on the EDT.
	 * Hiding alone already suspends following, so ordinary panels need no close hook.
	 */
	@Override public void close() {
		if (!closed.compareAndSet(false,true)) return;
		Runnable release=() -> {
			follow();
			owner.removeHierarchyListener(hierarchy);
			bindings.clear();
		};
		if (EventQueue.isDispatchThread()) release.run();
		else EventQueue.invokeLater(release);
	}

	private static void checkEDT() {
		if (!EventQueue.isDispatchThread()) throw new IllegalStateException("Bindings must be configured on the EDT");
	}

	private static final class Binding<T> {
		private final Supplier<T> read;
		private final Consumer<T> display;
		private boolean initial=true;
		private T shown;

		Binding(Supplier<T> read,Consumer<T> display) {
			this.read=read;
			this.display=display;
		}

		Runnable sample() {
			T value=read.get();
			return () -> {
				if (initial || !Objects.equals(value,shown)) {
					display.accept(value);
					shown=value;
					initial=false;
				}
			};
		}
	}
}
