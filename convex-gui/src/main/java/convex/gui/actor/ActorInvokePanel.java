package convex.gui.actor;

import javax.swing.DefaultListModel;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.cvm.State;
import convex.core.data.ASet;
import convex.core.cvm.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Symbol;
import convex.gui.components.ScrollyList;
import convex.gui.components.account.AccountChooserPanel;
import convex.gui.models.StateModel;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class ActorInvokePanel extends JPanel {

	protected Address contract;
	AccountChooserPanel execPanel;

	DefaultListModel<Symbol> exportList = new DefaultListModel<Symbol>();
	private StateModel<State> model;

	public ActorInvokePanel(Convex convex,StateModel<State> model, Address contract) {
		this.contract = contract;
		this.model=model;
		

		setLayout(new MigLayout());

		execPanel=new AccountChooserPanel(convex);
		add(execPanel, "dock north");

		AccountStatus as = model.getValue().getAccount(contract);
		ASet<Symbol> exports = as.getCallableFunctions();
		for (Symbol s : exports) {
			System.err.println(s);
			exportList.addElement(s);
		}

		ScrollyList<Symbol> scrollyList = new ScrollyList<Symbol>(exportList,
				sym -> new SmartOpComponent(this, contract, sym));
		add(scrollyList, "dock center");

	}

	public State getLatestState() {
		return model.getValue();
	}

}
