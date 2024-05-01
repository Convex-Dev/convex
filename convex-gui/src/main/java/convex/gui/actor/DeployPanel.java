package convex.gui.actor;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

import convex.gui.components.ActionPanel;
import convex.gui.components.account.AccountChooserPanel;

@SuppressWarnings("serial")
public class DeployPanel extends JPanel {

	private AccountChooserPanel acctChooser;

	public DeployPanel() {
		setLayout(new BorderLayout());

		// ===========================================
		// Top panel
		acctChooser = new AccountChooserPanel(null);
		this.add(acctChooser, BorderLayout.NORTH);

		// ===========================================
		// Centre panel
		JPanel centrePanel = new JPanel();
		this.add(centrePanel, BorderLayout.CENTER);

		centrePanel.add(new JLabel("Tool for deploying standard Actors"));

		// ============================================
		// Action buttons
		ActionPanel actionPanel = new ActionPanel();
		add(actionPanel, BorderLayout.SOUTH);

		JButton deployButton = new JButton("Deploy...");
		actionPanel.add(deployButton);
		deployButton.addActionListener(e -> {

		});
	}
}
