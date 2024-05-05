package convex.gui.wallet;

import convex.api.Convex;
import convex.gui.components.AbstractGUI;

@SuppressWarnings("serial")
public class SwapPanel extends AbstractGUI {

	protected Convex convex;

	private SwapPanel(String title) {
		super(title);
	}

	public SwapPanel(Convex convex) {
		this("Token Swap for account "+convex.getAddress());
		this.convex=convex;
	}

}
