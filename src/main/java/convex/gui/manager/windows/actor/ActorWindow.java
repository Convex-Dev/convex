package convex.gui.manager.windows.actor;

import java.awt.BorderLayout;

import javax.swing.JTabbedPane;

import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.gui.manager.PeerGUI;
import convex.gui.manager.windows.BaseWindow;
import convex.gui.manager.windows.state.StateTreePanel;

@SuppressWarnings("serial")
public class ActorWindow extends BaseWindow {
	Address contract;

	JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

	public ActorWindow(PeerGUI manager, Address contract) {
		super(manager);
		this.contract = contract;
		AccountStatus as = PeerGUI.getLatestState().getAccount(contract);

		PeerGUI.getStateModel().addPropertyChangeListener(e -> {

		});

		add(tabbedPane, BorderLayout.CENTER);

		tabbedPane.add("Overview", new ActorInfoPanel(manager, contract));
		tabbedPane.add("Environment", new StateTreePanel(as.getEnvironment()));
		tabbedPane.add("Operations", new ActorInvokePanel(manager, contract));
	}

	@Override
	public String getTitle() {
		try {
			return "Contract view - " + contract.toHexString();
		} catch (Exception e) {
			return "Contract view - Unknown";
		}
	}

}
