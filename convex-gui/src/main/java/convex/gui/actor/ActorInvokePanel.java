package convex.gui.actor;

import java.awt.BorderLayout;

import javax.swing.DefaultListModel;
import javax.swing.JPanel;

import convex.core.State;
import convex.core.data.ASet;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Symbol;
import convex.gui.components.AccountChooserPanel;
import convex.gui.components.ScrollyList;
import convex.gui.peer.PeerGUI;

@SuppressWarnings("serial")
public class ActorInvokePanel extends JPanel {

	protected PeerGUI manager;
	protected Address contract;
	AccountChooserPanel execPanel;

	DefaultListModel<Symbol> exportList = new DefaultListModel<Symbol>();

	public ActorInvokePanel(PeerGUI manager, Address contract) {
		this.manager = manager;
		this.contract = contract;
		
		execPanel=new AccountChooserPanel(null,manager.getClientConvex(contract));
		add(execPanel, BorderLayout.NORTH);

		setLayout(new BorderLayout());

		AccountStatus as = manager.getLatestState().getAccount(contract);
		ASet<Symbol> exports = as.getCallableFunctions();
		for (Symbol s : exports) {
			exportList.addElement(s);
		}

		ScrollyList<Symbol> scrollyList = new ScrollyList<Symbol>(exportList,
				sym -> new SmartOpComponent(this, contract, sym));
		add(scrollyList, BorderLayout.CENTER);

	}

	public State getLatestState() {
		return manager.getLatestState();
	}

}
