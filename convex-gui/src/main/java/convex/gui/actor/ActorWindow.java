package convex.gui.actor;

import javax.swing.JTabbedPane;

import convex.api.Convex;
import convex.core.State;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.gui.components.AbstractGUI;
import convex.gui.components.models.StateModel;
import convex.gui.peer.windows.state.StateTreePanel;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class ActorWindow extends AbstractGUI {
	Address contract;

	JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

	public ActorWindow(Convex convex,StateModel<State> manager, Address contract) {
		super ("Contract view - " + contract);
		this.contract = contract;
		setLayout(new MigLayout());
		AccountStatus as = manager.getValue().getAccount(contract);

		manager.addPropertyChangeListener(e -> {

		});

		add(tabbedPane, "dock center");

		tabbedPane.add("Overview", new ActorInfoPanel(manager, contract));
		tabbedPane.add("Environment", new StateTreePanel(as.getEnvironment()));
		tabbedPane.add("Operations", new ActorInvokePanel(convex,manager, contract));
	}

}
