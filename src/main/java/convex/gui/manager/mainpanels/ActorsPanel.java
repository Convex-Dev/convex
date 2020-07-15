package convex.gui.manager.mainpanels;

import java.awt.BorderLayout;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import convex.core.Init;
import convex.gui.manager.PeerManager;
import convex.gui.manager.mainpanels.actors.DeployPanel;
import convex.gui.manager.mainpanels.actors.MarketsPanel;
import convex.gui.manager.mainpanels.actors.OraclePanel;
import convex.gui.manager.windows.actor.ActorInvokePanel;

/**
 * Top level panel that displays some standard Actors
 */
@SuppressWarnings("serial")
public class ActorsPanel extends JPanel {

	private JTabbedPane typePane;

	public ActorsPanel(PeerManager manager) {
		setLayout(new BorderLayout(0, 0));

		typePane = new JTabbedPane();

		typePane.add("Oracle", new OraclePanel());

		typePane.add("Registry", new ActorInvokePanel(manager, Init.REGISTRY_ADDRESS));

		typePane.add("Prediction Markets", new MarketsPanel(manager));

		typePane.add("Deploy", new DeployPanel());

		add(typePane, BorderLayout.CENTER);
	}

}
