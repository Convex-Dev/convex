package convex.gui.manager.mainpanels.actors;

import java.awt.BorderLayout;
import java.util.logging.Logger;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JPanel;

import convex.core.data.Address;
import convex.gui.components.AccountChooserPanel;
import convex.gui.components.ActionPanel;
import convex.gui.components.ScrollyList;
import convex.gui.manager.PeerManager;

/**
 * Panel displaying current prediction markets
 */
@SuppressWarnings("serial")
public class MarketsPanel extends JPanel {

	private static final Logger log = Logger.getLogger(MarketsPanel.class.getName());
	AccountChooserPanel acctChooser;

	static DefaultListModel<Address> marketList = new DefaultListModel<Address>();

	public MarketsPanel(PeerManager manager) {
		this.setLayout(new BorderLayout());

		// ===========================================
		// Top panel
		acctChooser = new AccountChooserPanel();
		this.add(acctChooser, BorderLayout.NORTH);

		// ===========================================
		// Central scrolling list
		ScrollyList<Address> scrollyList = new ScrollyList<Address>(marketList,
				addr -> new MarketComponent(this, addr));
		add(scrollyList, BorderLayout.CENTER);

		// ============================================
		// Action buttons
		ActionPanel actionPanel = new ActionPanel();
		add(actionPanel, BorderLayout.SOUTH);

		JButton scanButton = new JButton("Scan");
		actionPanel.add(scanButton);
		scanButton.addActionListener(e -> {
			log.info("Scanning for prediction market Actors...");
		});
	}
}
