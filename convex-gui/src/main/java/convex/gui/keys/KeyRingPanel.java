package convex.gui.keys;

import java.awt.Color;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.gui.components.ActionPanel;
import convex.gui.components.ScrollyList;
import convex.gui.components.Toast;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

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
		setLayout(new MigLayout());

		// Scrollable list of wallet entries
		walletList = new ScrollyList<AWalletEntry>(listModel, we -> new WalletComponent(we));
		add(walletList, "dock center");

		// Action toolbar
		JPanel toolBar = new ActionPanel();

		// new wallet button
		JButton btnNew = new JButton("New Keypair");
		btnNew.setToolTipText("Create a new hot wallet keypair. Use for temporary purposes. Remember to save the seed if you want to re-use!");
		toolBar.add(btnNew);
		btnNew.addActionListener(e -> {
			AKeyPair newKP=AKeyPair.generate();
			try {
				listModel.addElement(HotWalletEntry.create(newKP));
			} catch (Exception  t) {
				Toast.display(this,"Error creating key pair: "+t.getMessage(),Color.RED);
				t.printStackTrace();
			}
		});
		
		// new wallet button
		JButton btnImportSeed = new JButton("Import Seed....");
		btnImportSeed.setToolTipText("Import a key pair using an Ed25519 seed");
		toolBar.add(btnImportSeed);
		btnImportSeed.addActionListener(e -> {
			String sd=JOptionPane.showInputDialog("Enter Ed25519 Seed");
			if (sd==null) return;
			Blob seed=Blob.parse(sd);
			if (seed==null) return;
			
			try {
				AKeyPair newKP=AKeyPair.create(seed);
				listModel.addElement(HotWalletEntry.create(newKP));
			} catch (Exception  t) {
				Toast.display(this,"Exception importing seed: "+t.getMessage(),Color.RED);
				t.printStackTrace();
			}
		});
		
		add(toolBar, "dock south");

	}

	public static DefaultListModel<AWalletEntry> getListModel() {
		return listModel;
	}

	/**
	 * Gets the correct keypair for a convex connection
	 * @param convex Convex instance (assumes address is set)
	 * @return KeyPair resolved, or null if not available
	 */
	public static AWalletEntry findWalletEntry(Convex convex) {
		Address a=convex.getAddress();
		if (a==null) return null;
		AccountKey key=convex.getAccountKey(a);
		AWalletEntry we=Toolkit.getKeyRingEntry(key);
		return we;
	}
}
