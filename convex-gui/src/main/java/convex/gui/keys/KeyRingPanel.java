package convex.gui.keys;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Constants;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.core.crypto.wallet.KeystoreWalletEntry;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.lang.RT;
import convex.core.lang.Symbols;
import convex.core.lang.ops.Special;
import convex.core.util.FileUtils;
import convex.core.util.Utils;
import convex.gui.components.ActionButton;
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
	
	static {
		try {
			File f=FileUtils.getFile(Constants.DEFAULT_KEYSTORE_FILENAME);
			loadKeys(f);
		} catch (Exception e) {
			log.warn("Failed to load default key store: "+e.getMessage());
		}
	}

	/**
	 * Create the panel.
	 */
	public KeyRingPanel() {
		setLayout(new MigLayout("fill"));
		
		// Scrollable list of wallet entries
		walletList = new ScrollyList<AWalletEntry>(listModel, we -> new WalletComponent(we));
		add(walletList, "dock center");

		// Action toolbar
		JPanel toolBar = new ActionPanel();

		// New keypair button
		JButton btnNew = new ActionButton("New Keypair",0xe145,e -> {
			AKeyPair newKP=AKeyPair.generate();
			try {
				listModel.addElement(HotWalletEntry.create(newKP,"Generated key (in memory)"));
				Toolkit.scrollToBottom(walletList);
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
				listModel.addElement(HotWalletEntry.create(newKP, "Imported from Ed25519 seed"));
			} catch (Exception  t) {
				Toast.display(this,"Exception importing seed: "+t.getMessage(),Color.RED);
				t.printStackTrace();
			}
		});
		btnImportSeed.setToolTipText("Import a key pair using an Ed25519 seed");
		toolBar.add(btnImportSeed);
		
		
		add(toolBar, "dock south");

	}

	/**
	 * Load keys from a file. Returns number of keys loaded, or -1 if file could not be opened 
	 * @param f
	 * @return
	 */
	private static int loadKeys(File f) {
		if (!f.exists()) return -1;
		try {
			KeyStore ks=PFXTools.loadStore(f, null);
			return loadKeys(ks, f.getCanonicalPath());
		} catch (IOException e) {
			log.debug("Can't load key store: "+e.getMessage());
			return -1;
		} catch (GeneralSecurityException e) {
			log.debug("Can't load key store: "+e.getMessage());
			return -1;
		}
	}

	private static int loadKeys(KeyStore keyStore, String source) throws KeyStoreException {
		int n=0;
		Enumeration<String> aliases = keyStore.aliases();
		while (aliases.hasMoreElements()) {
			String alias = aliases.nextElement();
			AWalletEntry we=KeystoreWalletEntry.create(keyStore, alias, source);
			listModel.addElement(we);
			n++;
		}
		return n;
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
		AccountKey key;
		try {
			CompletableFuture<ACell> cf=convex.query(Special.forSymbol(Symbols.STAR_KEY)).thenApply(r->r.getValue());
			key = RT.ensureAccountKey(cf.get());
			AWalletEntry we=KeyRingPanel.getKeyRingEntry(key);
			return we;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Gets an entry for the current keyring
	 * @param publicKey Public key to search for
	 * @return Wallet Entry, or null if not found
	 */
	public static AWalletEntry getKeyRingEntry(AccountKey publicKey) {
		if (publicKey==null) return null;
		DefaultListModel<AWalletEntry> list = getListModel();
		Iterator<AWalletEntry> it=list.elements().asIterator();
		while (it.hasNext()) {
			AWalletEntry we=it.next();
			if (Utils.equals(we.getPublicKey(), publicKey)) {
				return we;
			}
		}
		return null;
	}
}
