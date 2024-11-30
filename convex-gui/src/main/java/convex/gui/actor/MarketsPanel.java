package convex.gui.actor;

import java.awt.BorderLayout;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JPanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.ConvexLocal;
import convex.core.cvm.State;
import convex.core.cvm.Address;
import convex.gui.components.ActionPanel;
import convex.gui.components.ScrollyList;
import convex.gui.components.account.AccountChooserPanel;

/**
 * Panel displaying current prediction markets
 */
@SuppressWarnings("serial")
public class MarketsPanel extends JPanel {

	private static final Logger log = LoggerFactory.getLogger(MarketsPanel.class.getName());
	AccountChooserPanel acctChooser;
	private ConvexLocal manager;

	static DefaultListModel<Address> marketList = new DefaultListModel<Address>();

	public MarketsPanel(ConvexLocal peer) {
		this.setLayout(new BorderLayout());
		this.manager=peer;

		// ===========================================
		// Top panel
		acctChooser = new AccountChooserPanel(peer);
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

	public State getLatestState() {
		return manager.getState();
	}
	
}
