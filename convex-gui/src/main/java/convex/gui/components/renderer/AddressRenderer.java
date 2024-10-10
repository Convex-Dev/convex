package convex.gui.components.renderer;

import javax.swing.JLabel;

import convex.core.data.Address;
import convex.core.util.Utils;

@SuppressWarnings("serial")
public class AddressRenderer extends CellRenderer {

	public AddressRenderer(int alignment) {
		super(alignment);
	}

	public AddressRenderer() {
		super(JLabel.LEFT);
	}

	@Override
	public void setValue(Object o) {
		String s=Utils.toString(Address.parse(o));
		super.setValue(s);
	}
}
