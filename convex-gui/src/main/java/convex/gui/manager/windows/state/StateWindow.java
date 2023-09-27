package convex.gui.manager.windows.state;

import java.awt.BorderLayout;

import javax.swing.JTabbedPane;

import convex.core.data.ACell;
import convex.gui.PeerGUI;
import convex.gui.manager.windows.BaseWindow;

@SuppressWarnings("serial")
public class StateWindow extends BaseWindow {

	JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

	public StateWindow(PeerGUI manager, ACell state) {
		super(manager);

		add(tabbedPane, BorderLayout.CENTER);

		tabbedPane.addTab("State Tree", null, new StateTreePanel(state), null);

	}

	@Override
	public String getTitle() {
		return "State explorer";
	}

}
