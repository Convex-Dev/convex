package convex.gui.components;

import javax.swing.JPanel;
import javax.swing.border.BevelBorder;

import net.miginfocom.swing.MigLayout;

/**
 * A panel used for displaying a list of action buttons at the bottom of the
 * screen.
 */
@SuppressWarnings("serial")
public class ActionPanel extends JPanel {

	public ActionPanel() {
		super();

		MigLayout layout = new MigLayout();
		setLayout(layout);

		setBorder(new BevelBorder(BevelBorder.RAISED, null, null, null, null));
	}
}
