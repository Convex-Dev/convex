package convex.gui.models;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
// import java.util.logging.Logger;

import javax.swing.SwingUtilities;

/**
 * Model for state values which may be observer / listened to.
 * 
 * Fires a property changed event for the property "value" whenever it is
 * updated.
 * 
 * @param <T> Type of State value
 */
public class StateModel<T> {
	
	// private static final Logger log = Logger.getLogger(StateModel.class.getName());

	
	private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);

	T value;

	public StateModel(T value) {
		this.value = value;
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
	 * Sets the value for this state model, firing any relevant property change
	 * listeners.
	 * 
	 * @param newValue New value for state
	 */
	public void setValue(T newValue) {
		T oldValue = this.value;
		this.value = newValue;
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				// log.info("State update reported");
				propertyChangeSupport.firePropertyChange(new PropertyChangeEvent(this, "value", oldValue, newValue));
			}			
		});
	}

	public void addPropertyChangeListener(PropertyChangeListener listener) {
		propertyChangeSupport.addPropertyChangeListener(listener);
	}

	public void removePropertyChangeListener(PropertyChangeListener listener) {
		propertyChangeSupport.removePropertyChangeListener(listener);
	}
}
