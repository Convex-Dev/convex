package convex.gui.manager.mainpanels;

import java.awt.BorderLayout;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.ListModel;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.WalletEntry;
import convex.gui.components.ActionPanel;
import convex.gui.components.ScrollyList;
import convex.gui.components.WalletComponent;
import convex.gui.manager.PeerGUI;

@SuppressWarnings("serial")
public class WalletPanel extends JPanel {

	public static WalletEntry HERO;

	private static DefaultListModel<WalletEntry> listModel = new DefaultListModel<>();;
	ScrollyList<WalletEntry> walletList;

	public void addWalletEntry(WalletEntry we) {
		listModel.addElement(we);
	}

	/**
	 * Create the panel.
	 */
	public WalletPanel() {
		setLayout(new BorderLayout(0, 0));

		JPanel toolBar = new ActionPanel();
		add(toolBar, BorderLayout.SOUTH);

		// new wallet button
		JButton btnNew = new JButton("New");
		toolBar.add(btnNew);
		btnNew.addActionListener(e -> {
			listModel.addElement(WalletEntry.create(null,AKeyPair.generate()));
		});

		// inital list
        HERO = WalletEntry.create(PeerGUI.initConfigTest.HERO_ADDRESS, PeerGUI.initConfigTest.HERO_KEYPAIR);
		addWalletEntry(HERO);
		addWalletEntry(WalletEntry.create(PeerGUI.initConfigTest.VILLAIN_ADDRESS, PeerGUI.initConfigTest.VILLAIN_KEYPAIR));
		addWalletEntry(WalletEntry.create(PeerGUI.initConfigTest.getPeerAddress(0),PeerGUI.initConfigTest.getPeerKeyPair(0)));

		// create and add ScrollyList
		walletList = new ScrollyList<WalletEntry>(listModel, we -> new WalletComponent(we));
		add(walletList, BorderLayout.CENTER);
	}

	public static ListModel<WalletEntry> getListModel() {
		return listModel;
	}

}
