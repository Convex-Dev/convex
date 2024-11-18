package convex.gui.wallet;

import javax.swing.JPanel;

import convex.api.Convex;
import convex.gui.client.ConvexClient;
import convex.gui.components.ActionButton;
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
		add(Toolkit.withTitledBorder("Account Selection", chooser));
		
		ActionButton replButton=new ActionButton("Terminal REPL", 0xeb8e, e->{
			new ConvexClient(convex).run();
		});
		JPanel toolPanel=new JPanel();
		toolPanel.add(replButton);
		add(Toolkit.withTitledBorder("Developer Tools", toolPanel));
	}
}
