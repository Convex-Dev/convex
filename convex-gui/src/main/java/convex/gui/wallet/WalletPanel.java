package convex.gui.wallet;

import javax.swing.JPanel;

import convex.api.Convex;
import convex.gui.components.AccountChooserPanel;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class WalletPanel extends JPanel {

	public WalletPanel(Convex convex) {
		setLayout(new MigLayout("fill"));
		
		// add(new JLabel(SymbolIcon.get(0xe14a)));

		add(new AccountChooserPanel(convex),"dock south");
		
	}
}
