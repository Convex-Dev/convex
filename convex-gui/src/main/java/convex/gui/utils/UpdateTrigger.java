package convex.gui.utils;

import java.beans.PropertyChangeListener;
import java.util.Objects;

import javax.swing.Timer;

import convex.gui.models.StateModel;

/**
 * Signals that a view should read its source again. Subscription and the returned
 * unsubscribe action run on the EDT; signals may arrive on any thread. A signal
 * carries no state and may be coalesced. Each subscription has its own lifetime.
 */
@FunctionalInterface
public interface UpdateTrigger {

	/** Starts signalling and returns the action that stops this subscription. */
	Runnable subscribe(Runnable refresh);

	/** A local sampling trigger; the timer exists only for the subscription's lifetime. */
	static UpdateTrigger polling(int millis) {
		if (millis<=0) throw new IllegalArgumentException("Polling interval must be positive");
		return refresh -> {
			Timer timer=new Timer(millis,e -> refresh.run());
			timer.start();
			return timer::stop;
		};
	}

	/** Adapts an existing GUI model's events, without polling. */
	static UpdateTrigger changes(StateModel<?> model) {
		Objects.requireNonNull(model);
		return refresh -> {
			PropertyChangeListener listener=e -> refresh.run();
			model.addPropertyChangeListener(listener);
			return () -> model.removePropertyChangeListener(listener);
		};
	}
}
