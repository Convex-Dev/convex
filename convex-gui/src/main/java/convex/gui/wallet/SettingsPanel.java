package convex.gui.wallet;

import javax.swing.JPanel;

import convex.api.Convex;

@SuppressWarnings("serial")
public class SettingsPanel extends JPanel {

	protected Convex convex;

	public SettingsPanel(Convex convex) {
		this.convex=convex;
	}
}
