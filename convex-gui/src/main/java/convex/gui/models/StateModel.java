package convex.gui.models;

import java.awt.EventQueue;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Model for state values which may be observed.
 *
 * <p>The value may be set from any thread. Listeners are told of changes on
 * the EDT, with a property change event for the property "value": a burst of
 * updates arrives as one event with the latest value, and setting an equal
 * value fires nothing.
 *
 * @param <T> Type of State value
 */
public class StateModel<T> {

	private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);

	private volatile T value;

	/** Value listeners were last told about. EDT only. */
	private T notified;

	/** Set while an event is waiting to fire */
	private final AtomicBoolean pending=new AtomicBoolean();

	public StateModel(T value) {
		this.value = value;
		this.notified = value;
	}

	public StateModel() {
		this(null);
	}

	public static <T> StateModel<T> create(T value) {
		return new StateModel<T>(value);
	}

	public T getValue() {
		return value;
	}

	/**
	 * Sets the value for this state model, telling listeners on the EDT.
	 * Safe from any thread.
	 *
	 * @param newValue New value for state
	 */
	public void setValue(T newValue) {
		this.value = newValue;
		if (pending.compareAndSet(false, true)) {
			EventQueue.invokeLater(this::fireChange);
		}
	}

	private void fireChange() {
		pending.set(false);
		T oldValue=notified;
		T newValue=value;
		notified=newValue;
		propertyChangeSupport.firePropertyChange(new PropertyChangeEvent(this, "value", oldValue, newValue));
	}

	public void addPropertyChangeListener(PropertyChangeListener listener) {
		propertyChangeSupport.addPropertyChangeListener(listener);
	}

	public void removePropertyChangeListener(PropertyChangeListener listener) {
		propertyChangeSupport.removePropertyChangeListener(listener);
	}
}
