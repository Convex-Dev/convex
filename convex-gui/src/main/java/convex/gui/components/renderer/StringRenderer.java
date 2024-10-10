package convex.gui.components.renderer;

import javax.swing.JLabel;

@SuppressWarnings("serial")
public class StringRenderer extends CellRenderer {
	public StringRenderer(int alignment) {
		super(alignment);
	}
	
	public StringRenderer() {
		super(JLabel.RIGHT);
	}

}
