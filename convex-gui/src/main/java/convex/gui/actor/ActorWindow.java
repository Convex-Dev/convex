package convex.gui.actor;

import java.awt.BorderLayout;

import javax.swing.JTabbedPane;

import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.gui.peer.PeerGUI;
import convex.gui.peer.windows.BaseWindow;
import convex.gui.peer.windows.state.StateTreePanel;

@SuppressWarnings("serial")
public class ActorWindow extends BaseWindow {
	Address contract;

	JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

	public ActorWindow(PeerGUI manager, Address contract) {
		super(manager);
		this.contract = contract;
		AccountStatus as = manager.getLatestState().getAccount(contract);

		manager.getStateModel().addPropertyChangeListener(e -> {

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
