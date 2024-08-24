package convex.gui.actor;

import javax.swing.JFrame;
import javax.swing.JTabbedPane;

import convex.api.Convex;
import convex.core.State;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.gui.components.AbstractGUI;
import convex.gui.models.StateModel;
import convex.gui.state.StateTreePanel;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class AccountWindow extends AbstractGUI {
	Address account;

	JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

	public AccountWindow(Convex convex,StateModel<State> manager, Address account) {
		super ("Account view - " + account);
		this.account = account;
		setLayout(new MigLayout());
		AccountStatus as = manager.getValue().getAccount(account);


		manager.addPropertyChangeListener(e -> {

		});

		add(tabbedPane, "dock center");

		tabbedPane.add("Overview", new AccountInfoPanel(manager, account));
		tabbedPane.add("Environment", new StateTreePanel((as==null)?null:as.getEnvironment()));
		tabbedPane.add("Operations", new ActorInvokePanel(convex,manager, account));
	}

	@Override
	public void setupFrame(JFrame frame) {
		frame.getContentPane().setLayout(new MigLayout());
		frame.getContentPane().add(this,"dock center");
	}

}
