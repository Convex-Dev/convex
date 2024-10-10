package convex.gui.components.renderer;

import javax.swing.BorderFactory;
import javax.swing.table.DefaultTableCellRenderer;

import convex.core.data.AArrayBlob;
import convex.core.util.Utils;
import convex.gui.components.Identicon;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class AccountKeyRenderer extends DefaultTableCellRenderer {
	public AccountKeyRenderer() {
		super();
		this.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
		this.setFont(Toolkit.SMALL_MONO_FONT);
	}

	public void setValue(Object value) {
		if (value==null) {
			setText("-");
			setIcon(null);
		} else if (value instanceof AArrayBlob) {
			setText(value.toString());
			setIcon(Identicon.createIcon((AArrayBlob) value,21));
		} else {
			setText("Unexpected: "+Utils.getClassName(value));
			setIcon(null);
		}
	}
}