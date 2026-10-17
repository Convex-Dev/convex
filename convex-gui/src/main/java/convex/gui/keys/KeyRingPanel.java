package convex.gui.keys;

import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.filechooser.*;

import convex.core.Constants;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.core.data.Blob;
import convex.core.util.FileUtils;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.ScrollyList;
import convex.gui.components.Toast;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * A GUI panel displaying the user's current keypairs, from the global {@link KeyRing}
 */
@SuppressWarnings("serial")
public class KeyRingPanel extends JPanel {

	ScrollyList<AWalletEntry> walletList;

	/**
	 * Create the panel.
	 */
	public KeyRingPanel() {
		setLayout(new MigLayout("fill"));
		KeyRing keyRing=KeyRing.get();

		// Scrollable list of wallet entries
		walletList = new ScrollyList<AWalletEntry>(keyRing.listModel(), we -> new WalletComponent(we));
		add(walletList, "dock center");

		// Action toolbar
		JPanel toolBar = new ActionPanel();

		// New keypair button
		JButton btnNew = new ActionButton("New Keypair",0xe145,e -> {
			AKeyPair newKP=AKeyPair.generate();
			try {
				keyRing.add(HotWalletEntry.create(newKP,"Generated key (in memory)"));
				// Queued after the key ring's own view update
				EventQueue.invokeLater(()->Toolkit.scrollToBottom(walletList));
			} catch (Exception  t) {
				Toast.display(this,"Error creating key pair: "+t.getMessage(),Color.RED);
				t.printStackTrace();
			}
		});
		btnNew.setToolTipText("Create a new random hot wallet keypair. Remember to save the key if you want to re-use!");
		toolBar.add(btnNew);

		// Import seed button
		JButton btnImportSeed = new ActionButton("Import Seed....",0xe890,e -> {
			String sd=(String) JOptionPane.showInputDialog(this,"Enter Ed25519 Seed","Import private key",JOptionPane.QUESTION_MESSAGE,Toolkit.menuIcon(0xe890),null,"");
			if (sd==null) return;
			Blob seed=Blob.parse(sd);
			if (seed==null) return;

			try {
				AKeyPair newKP=AKeyPair.create(seed);
				boolean added=keyRing.add(HotWalletEntry.create(newKP, "Imported from Ed25519 seed"));
				if (!added) {
					Toast.display(this,"Key already present in key ring",Color.ORANGE);
				}
			} catch (Exception  t) {
				Toast.display(this,"Exception importing seed: "+t.getMessage(),Color.RED);
				t.printStackTrace();
			}
		});
		btnImportSeed.setToolTipText("Import a key pair using an Ed25519 seed");
		toolBar.add(btnImportSeed);

		// Import seed button
		JButton btnLoadKeys = new ActionButton("Load Keys....",0xe890,this::loadStore);
		btnLoadKeys.setToolTipText("Load keys from a Keystore file");
		toolBar.add(btnLoadKeys);



		add(toolBar, "dock south");

	}


	private void loadStore(ActionEvent e) {
    	try {
    		File f=chooseKeyStore(this,"Load");
	    	if (f==null) return;
			if (f.exists()) {
				KeyStore ks=PFXTools.loadStore(f, null);
				int num=KeyRing.get().load(ks,"Loaded from "+f.getCanonicalPath());
				Toolkit.showMessge(this,num+" new keys loaded.");
			}
		} catch (IOException | GeneralSecurityException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	}

	/**
	 * Shows dialog to choose a key store
	 * @param parent
	 * @return
	 */
	private static File chooseKeyStore(Component parent,String action) {
		JFileChooser chooser = new JFileChooser();
	    FileNameExtensionFilter filter = new FileNameExtensionFilter("PKCS #12 Keystore", "p12", "pfx");
	    chooser.setFileFilter(filter);
	    File defaultDir=FileUtils.getFile(Constants.DEFAULT_KEYSTORE_FILENAME);
	    if (defaultDir.isDirectory()) {
		    chooser.setCurrentDirectory(defaultDir);
	    } else {
	    	chooser.setCurrentDirectory(defaultDir.getParentFile());
	    }
	    int returnVal = chooser.showDialog(parent,action);
	    if(returnVal != JFileChooser.APPROVE_OPTION) return null;
	    File f=chooser.getSelectedFile();
		return f;
	}

	/**
	 * Save a key to a store, prompting as necessary
	 * @param parent Parent component for prompting
	 * @param walletEntry Wallet entry to save
	 * @return True if saved successfully, false otherwise
	 */
	public static boolean saveKey(Component parent, AWalletEntry walletEntry) {
		boolean locked=walletEntry.isLocked();
		try {
			File f=chooseKeyStore(parent,"Save Key");
			if (f==null) return false;
			KeyStore ks;
			if (f.exists()) {
				ks=PFXTools.loadStore(f, null);
			} else {
				ks=PFXTools.createStore(f, null);
			}
			String s=JOptionPane.showInputDialog(parent,"Enter key encryption password");
			if (s==null) return false;

			if (locked) {
				boolean unlock=walletEntry.tryUnlock(s.toCharArray());
				if (!unlock) {
					Toolkit.showMessge(parent, "Could not unlock key with this password");
					return false;
				}
			}
			PFXTools.setKeyPair(ks, walletEntry.getKeyPair(), s.toCharArray());

			// Lock wallet entry again

			PFXTools.saveStore(ks, f, null);
			return true;
		} catch (Exception e) {
			Toolkit.showErrorMessage(parent,"Failed to save to KeyStore",e);
			return false;
		} finally {
			if (locked) walletEntry.lock();
		}
	}
}
