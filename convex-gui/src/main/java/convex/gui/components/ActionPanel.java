package convex.gui.components;

import java.awt.FlowLayout;

import javax.swing.JPanel;
import javax.swing.border.BevelBorder;

/**
 * A panel used for displaying a list of action buttons at the bottom of the
 * screen.
 */
@SuppressWarnings("serial")
public class ActionPanel extends JPanel {

	public ActionPanel() {
		super();

		FlowLayout flowLayout = new FlowLayout(FlowLayout.LEFT);
		setLayout(flowLayout);

		setBorder(new BevelBorder(BevelBorder.RAISED, null, null, null, null));
	}
}
