package convex.gui.actor;

import java.awt.BorderLayout;

import javax.swing.DefaultListModel;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.State;
import convex.core.data.ASet;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Symbol;
import convex.gui.components.AccountChooserPanel;
import convex.gui.components.ScrollyList;
import convex.gui.components.models.StateModel;

@SuppressWarnings("serial")
public class ActorInvokePanel extends JPanel {

	protected Address contract;
	AccountChooserPanel execPanel;

	DefaultListModel<Symbol> exportList = new DefaultListModel<Symbol>();
	private StateModel<State> model;

	public ActorInvokePanel(Convex manager,StateModel<State> model, Address contract) {
		this.contract = contract;
		this.model=model;
		
		execPanel=new AccountChooserPanel(null,manager);
		add(execPanel, BorderLayout.NORTH);

		setLayout(new BorderLayout());

		AccountStatus as = model.getValue().getAccount(contract);
		ASet<Symbol> exports = as.getCallableFunctions();
		for (Symbol s : exports) {
			exportList.addElement(s);
		}

		ScrollyList<Symbol> scrollyList = new ScrollyList<Symbol>(exportList,
				sym -> new SmartOpComponent(this, contract, sym));
		add(scrollyList, BorderLayout.CENTER);

	}

	public State getLatestState() {
		return model.getValue();
	}

}
