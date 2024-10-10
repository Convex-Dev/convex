package convex.gui.components.renderer;

import javax.swing.table.DefaultTableCellRenderer;

import convex.core.util.Utils;

@SuppressWarnings("serial")
public class CellRenderer extends DefaultTableCellRenderer {
	public CellRenderer(int alignment) {
		super();
		this.setHorizontalAlignment(alignment);
	}

	public void setValue(Object value) {
		setText(Utils.toString(value));
	}
	
}