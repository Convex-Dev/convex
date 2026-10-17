package convex.gui.keys;

import java.awt.EventQueue;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.swing.DefaultListModel;
import javax.swing.ListModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Constants;
import convex.core.crypto.PFXTools;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.KeystoreWalletEntry;
import convex.core.cvm.Address;
import convex.core.cvm.Symbols;
import convex.core.cvm.ops.Special;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.lang.RT;
import convex.core.util.FileUtils;
import convex.core.util.Utils;

/**
 * The user's key ring: the wallet entries available to the GUI, at most one per
 * public key. Process-global by design, and loaded from the default key store
 * on first use.
 *
 * <p>Thread-safe. The Swing view returned by {@link #listModel()} is changed
 * only on the event dispatch thread, in the same order as the key ring.
 */
public final class KeyRing {

	private static final Logger log = LoggerFactory.getLogger(KeyRing.class);

	/** Holds the global key ring, so the default key store is read on first use */
	private static final class Global {
		static final KeyRing INSTANCE=loadDefault();
	}

	/**
	 * Gets the user's key ring
	 * @return The global key ring
	 */
	public static KeyRing get() {
		return Global.INSTANCE;
	}

	/** Entries in insertion order. Guarded by this. */
	private final ArrayList<AWalletEntry> entries=new ArrayList<>();

	/** Swing view of the entries. Changed only on the EDT. */
	private final DefaultListModel<AWalletEntry> view=new DefaultListModel<>();

	/** Creates an empty key ring. The GUI uses the global one from {@link #get()}. */
	KeyRing() {
	}

	private static KeyRing loadDefault() {
		KeyRing ring=new KeyRing();
		try {
			ring.load(FileUtils.getFile(Constants.DEFAULT_KEYSTORE_FILENAME));
		} catch (Exception e) {
			log.warn("Failed to load default key store: "+e.getMessage());
		}
		return ring;
	}

	/**
	 * Adds a wallet entry, unless an entry with the same public key is present.
	 * @param entry Wallet entry to add
	 * @return true if added, false if an entry for the key was already present
	 */
	public boolean add(AWalletEntry entry) {
		synchronized (this) {
			if (find(entry.getPublicKey())!=null) {
				log.debug("Skipping duplicate wallet entry for key: {}",entry.getPublicKey());
				return false;
			}
			entries.add(entry);
			// Posted under the lock, so the view sees changes in key ring order
			EventQueue.invokeLater(()->view.addElement(entry));
		}
		log.debug("Wallet entry added to key ring: {}",entry.getPublicKey());
		return true;
	}

	/**
	 * Removes a wallet entry
	 * @param entry Wallet entry to remove
	 * @return true if the entry was present
	 */
	public boolean remove(AWalletEntry entry) {
		synchronized (this) {
			if (!entries.remove(entry)) return false;
			EventQueue.invokeLater(()->view.removeElement(entry));
		}
		return true;
	}

	/**
	 * Finds the entry for a public key
	 * @param publicKey Public key to search for
	 * @return Wallet entry, or null if not found
	 */
	public synchronized AWalletEntry find(AccountKey publicKey) {
		if (publicKey==null) return null;
		for (AWalletEntry we: entries) {
			if (Utils.equals(we.getPublicKey(), publicKey)) return we;
		}
		return null;
	}

	/**
	 * Finds the entry for the key of a connection's current account. Blocks
	 * while the account key is queried.
	 * @param convex Connection (assumes address is set)
	 * @return Wallet entry, or null if not available
	 */
	public AWalletEntry findFor(Convex convex) {
		Address a=convex.getAddress();
		if (a==null) return null;
		try {
			CompletableFuture<ACell> cf=convex.query(Special.forSymbol(Symbols.STAR_KEY)).thenApply(r->r.getValue());
			AccountKey key=RT.ensureAccountKey(cf.get());
			return find(key);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			log.info("Unable to query account key for {}",a,e);
			return null;
		}
	}

	/**
	 * @return Snapshot of the current entries, in insertion order
	 */
	public synchronized List<AWalletEntry> getEntries() {
		return List.copyOf(entries);
	}

	/**
	 * Gets a Swing view of the key ring, for list and combo box models. It is
	 * updated on the event dispatch thread and must only be read there.
	 * @return List model view of the entries
	 */
	public ListModel<AWalletEntry> listModel() {
		return view;
	}

	/**
	 * Loads keys from a key store file. Entries with an empty password are unlocked.
	 * @param f Key store file
	 * @return Number of new keys loaded, or -1 if the file could not be opened
	 */
	public int load(File f) {
		if (!f.exists()) return -1;
		try {
			KeyStore ks=PFXTools.loadStore(f, null);
			return load(ks, "Default KeyStore: "+f.getCanonicalPath());
		} catch (IOException | GeneralSecurityException e) {
			log.debug("Can't load key store: "+e.getMessage());
			return -1;
		}
	}

	/**
	 * Loads keys from a key store. Entries with an empty password are unlocked.
	 * @param keyStore Key store to load from
	 * @param source Description of the source, recorded on each entry
	 * @return Number of new keys loaded
	 * @throws KeyStoreException If the key store cannot be read
	 */
	public int load(KeyStore keyStore, String source) throws KeyStoreException {
		int numImports=0;
		Enumeration<String> aliases = keyStore.aliases();
		while (aliases.hasMoreElements()) {
			String alias = aliases.nextElement();
			AWalletEntry we=KeystoreWalletEntry.create(keyStore, alias, source);
			we.tryUnlock(null); // if empty password, unlock by default
			if (add(we)) numImports++;
		}
		return numImports;
	}
}
