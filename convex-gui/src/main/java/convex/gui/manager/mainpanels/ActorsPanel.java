package convex.gui.manager.mainpanels;

import java.awt.BorderLayout;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import convex.api.ConvexLocal;
import convex.gui.manager.mainpanels.actors.DeployPanel;
import convex.gui.manager.mainpanels.actors.MarketsPanel;
import convex.gui.manager.mainpanels.actors.OraclePanel;

/**
 * Top level panel that displays some standard Actors
 */
@SuppressWarnings("serial")
public class ActorsPanel extends JPanel {

	private JTabbedPane typePane;

	public ActorsPanel(ConvexLocal convex) {
		setLayout(new BorderLayout(0, 0));

		typePane = new JTabbedPane();

		typePane.add("Oracle", new OraclePanel(convex));

		// TODO: fix registry address
		// typePane.add("Registry", new ActorInvokePanel(manager, ...));

		typePane.add("Prediction Markets", new MarketsPanel(convex));

		typePane.add("Deploy", new DeployPanel());

		add(typePane, BorderLayout.CENTER);
	}

}
