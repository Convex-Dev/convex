package convex.gui.components.renderer;

import javax.swing.BorderFactory;
import javax.swing.table.DefaultTableCellRenderer;

import convex.core.data.AArrayBlob;
import convex.gui.components.Identicon;

@SuppressWarnings("serial")
public class AccountKeyRenderer extends DefaultTableCellRenderer {
	public AccountKeyRenderer() {
		super();
		this.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
	}

	public void setValue(Object value) {
		setText((value==null)?"":value.toString());
		setIcon(Identicon.createIcon((AArrayBlob) value,21));
	}
}