package convex.gui.components;

import javax.swing.JPanel;
import javax.swing.border.BevelBorder;
import javax.swing.border.CompoundBorder;

import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class BaseListComponent extends JPanel {

	public BaseListComponent() {
		setBorder(new CompoundBorder(new BevelBorder(BevelBorder.RAISED, null, null, null, null),
				Toolkit.createEmptyBorder(2)));

	}
}
