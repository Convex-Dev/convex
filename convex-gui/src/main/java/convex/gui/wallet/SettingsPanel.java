package convex.gui.wallet;

import javax.swing.JPanel;

import convex.api.Convex;
import convex.gui.components.account.AccountChooserPanel;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class SettingsPanel extends JPanel {

	protected Convex convex;

	public SettingsPanel(Convex convex) {
		this.convex=convex;
		
		this.setLayout(new MigLayout("wrap 1","[grow]"));

		AccountChooserPanel chooser=new AccountChooserPanel(convex);
		add(Toolkit.withTitledBorder("Account Selection", chooser),"dock north");
	}
}
