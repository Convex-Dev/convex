package convex.gui.manager.windows.actor;

import java.awt.BorderLayout;

import javax.swing.DefaultListModel;
import javax.swing.JPanel;

import convex.core.data.ASet;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Symbol;
import convex.gui.PeerGUI;
import convex.gui.components.AccountChooserPanel;
import convex.gui.components.ScrollyList;

@SuppressWarnings("serial")
public class ActorInvokePanel extends JPanel {

	protected PeerGUI manager;
	protected Address contract;
	AccountChooserPanel execPanel = new AccountChooserPanel();

	DefaultListModel<Symbol> exportList = new DefaultListModel<Symbol>();

	public ActorInvokePanel(PeerGUI manager, Address contract) {
		this.manager = manager;
		this.contract = contract;

		setLayout(new BorderLayout());

		AccountStatus as = PeerGUI.getLatestState().getAccount(contract);
		ASet<Symbol> exports = as.getCallableFunctions();
		for (Symbol s : exports) {
			exportList.addElement(s);
		}

		ScrollyList<Symbol> scrollyList = new ScrollyList<Symbol>(exportList,
				sym -> new SmartOpComponent(this, contract, sym));
		add(scrollyList, BorderLayout.CENTER);

		add(execPanel, BorderLayout.NORTH);
	}

}
