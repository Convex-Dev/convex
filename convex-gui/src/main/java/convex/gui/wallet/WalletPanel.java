package convex.gui.wallet;

import javax.swing.JPanel;

import convex.api.Convex;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class WalletPanel extends JPanel {

	public WalletPanel(Convex convex) {
		setLayout(new MigLayout("fill"));
		
		add(new AccountOverview(convex),"dock north");

		// add(new AccountChooserPanel(convex),"dock south");
		
	}
}
