package convex.gui.manager.windows.actor;

import java.awt.BorderLayout;

import javax.swing.DefaultListModel;
import javax.swing.JPanel;

import convex.core.data.ASet;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Symbol;
import convex.core.lang.Symbols;
import convex.gui.components.AccountChooserPanel;
import convex.gui.components.ScrollyList;
import convex.gui.manager.PeerManager;

@SuppressWarnings("serial")
public class ActorInvokePanel extends JPanel {

	protected PeerManager manager;
	protected Address contract;
	AccountChooserPanel execPanel = new AccountChooserPanel();

	DefaultListModel<Symbol> exportList = new DefaultListModel<Symbol>();

	public ActorInvokePanel(PeerManager manager, Address contract) {
		this.manager = manager;
		this.contract = contract;

		setLayout(new BorderLayout());

		AccountStatus as = PeerManager.getLatestState().getAccount(contract);
		ASet<Symbol> exports = as.getEnvironment().get(Symbols.STAR_EXPORTS).getValue();
		for (Symbol s : exports) {
			exportList.addElement(s);
		}

		ScrollyList<Symbol> scrollyList = new ScrollyList<Symbol>(exportList,
				sym -> new SmartOpComponent(this, contract, sym));
		add(scrollyList, BorderLayout.CENTER);

		add(execPanel, BorderLayout.NORTH);
	}

}
