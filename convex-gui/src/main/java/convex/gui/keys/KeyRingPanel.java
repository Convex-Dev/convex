package convex.gui.keys;

import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
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
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.filechooser.*; 

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Constants;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.core.crypto.wallet.KeystoreWalletEntry;
import convex.core.cvm.ops.Special;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.cvm.Address;
import convex.core.data.Blob;
import convex.core.lang.RT;
import convex.core.cvm.Symbols;
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
				int num=loadKeys(ks,"Loaded from "+f.getCanonicalPath());
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
	 * Load keys from a File. Returns number of keys loaded, or -1 if file could not be opened 
	 * @param f
	 * @return
	 */
	private static int loadKeys(File f) {
		if (!f.exists()) return -1;
		try {
			KeyStore ks=PFXTools.loadStore(f, null);
			return loadKeys(ks, "Default KeyStore: "+f.getCanonicalPath());
		} catch (IOException e) {
			log.debug("Can't load key store: "+e.getMessage());
			return -1;
		} catch (GeneralSecurityException e) {
			log.debug("Can't load key store: "+e.getMessage());
			return -1;
		}
	}

	private static int loadKeys(KeyStore keyStore, String source) throws KeyStoreException {
		int numImports=0;
		Enumeration<String> aliases = keyStore.aliases();
		while (aliases.hasMoreElements()) {
			String alias = aliases.nextElement();
			AWalletEntry we=KeystoreWalletEntry.create(keyStore, alias, source);
			we.tryUnlock(null); // if empty password, unlock by default
			AWalletEntry existing=getKeyRingEntry(we.getPublicKey());
			if (existing==null) {
				listModel.addElement(we);
				numImports++;
			}
		}
		return numImports;
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

	/**
	 * Save a key to a store, prompting as necessary
	 * @param parent
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
