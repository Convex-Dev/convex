package convex.gui.keys;

import java.awt.BorderLayout;
import java.awt.Color;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JPanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.gui.components.ActionPanel;
import convex.gui.components.ScrollyList;
import convex.gui.components.Toast;

/**
 * A GUI panel displaying the user's current keypairs
 */
@SuppressWarnings("serial")
public class KeyRingPanel extends JPanel {
	
	private static final Logger log = LoggerFactory.getLogger(KeyRingPanel.class.getName());

	private static DefaultListModel<AWalletEntry> listModel = new DefaultListModel<>();;
	ScrollyList<AWalletEntry> walletList;

	public static void addWalletEntry(AWalletEntry we) {
		listModel.addElement(we);
		log.debug("Wallet entry added to KeyRing: ",we.getPublicKey());
	}

	/**
	 * Create the panel.
	 */
	public KeyRingPanel() {
		setLayout(new BorderLayout(0, 0));

		JPanel toolBar = new ActionPanel();
		add(toolBar, BorderLayout.SOUTH);
		
		// create and add ScrollyList
		walletList = new ScrollyList<AWalletEntry>(listModel, we -> new WalletComponent(we));
		add(walletList, BorderLayout.CENTER);


		// new wallet button
		JButton btnNew = new JButton("New Keypair");
		toolBar.add(btnNew);
		btnNew.addActionListener(e -> {
			AKeyPair newKP=AKeyPair.generate();
			try {
				listModel.addElement(HotWalletEntry.create(newKP));
			} catch (Exception  t) {
				Toast.display(this,"Exception creating account: ",Color.RED);
				t.printStackTrace();
			}
		});
	}

	public static DefaultListModel<AWalletEntry> getListModel() {
		return listModel;
	}

}
