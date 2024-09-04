package convex.gui.models;

import javax.swing.DefaultComboBoxModel;

import convex.core.util.Utils;

@SuppressWarnings("serial")
public class ComboModel<E> extends DefaultComboBoxModel<E> {

	public ComboModel() {
		super();
	}

	public void ensureContains(E target) {
		if (!contains(target)) addElement(target);		
	}
	
	public boolean contains(E target) {
		int n=getSize();
		for (int i=0; i<n; i++) {
			if (Utils.equals(getElementAt(i),target)) return true;
		}
		return false;
	}
}
