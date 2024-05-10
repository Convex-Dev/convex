package convex.gui.actor;

import javax.swing.JLabel;
import javax.swing.SwingConstants;

import convex.gui.utils.Toolkit;

/**
 * Generic label component for displaying code
 */
@SuppressWarnings("serial")
public class ParamLabel extends JLabel {

	public ParamLabel(String text) {
		super("   " + text + "   ");
		this.setHorizontalAlignment(SwingConstants.RIGHT);
		this.setFont(Toolkit.MONO_FONT);
	}

}
