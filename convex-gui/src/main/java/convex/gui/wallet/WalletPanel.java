package convex.gui.wallet;

import javax.swing.JLabel;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.gui.components.AccountChooserPanel;
import convex.gui.utils.SymbolIcon;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class WalletPanel extends JPanel {

	public WalletPanel(Convex convex) {
		setLayout(new MigLayout());
		
		add(new AccountChooserPanel(convex),"dock north");
		
		add(new JLabel(SymbolIcon.get(0xe14a)));
	}
}
