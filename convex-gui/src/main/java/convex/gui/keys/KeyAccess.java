package convex.gui.keys;

import java.awt.Component;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javax.swing.JOptionPane;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.cvm.Address;
import convex.core.cvm.Symbols;
import convex.core.cvm.ops.Special;
import convex.core.data.AccountKey;
import convex.core.lang.RT;
import convex.gui.utils.Async;

/**
 * Getting key pairs from the key ring for use in the GUI.
 *
 * <p>Policy: using a key never changes the key ring. A window that needs a
 * locked key asks for its password, takes the key pair for its own use, and
 * leaves the entry locked. Only the key ring's own lock control changes a
 * key's state, so what the key ring shows is always true.
 */
public final class KeyAccess {

	private KeyAccess() {
	}

	/**
	 * Result of finding a signer for a connection: a key pair, or the reason
	 * there is none.
	 * @param keyPair Key pair to sign with, or null if unavailable
	 * @param reason Why no key pair is available, or null if there is one
	 */
	public record Signer(AKeyPair keyPair, String reason) {
		static Signer none(String reason) {
			return new Signer(null, reason);
		}
	}

	/**
	 * Gets the key pair of a wallet entry for one use, asking for its password
	 * if it is locked. The entry is left as it was. Call on the EDT.
	 * @param parent Parent component for the password dialog
	 * @param entry Wallet entry (may be null)
	 * @return Key pair, or null if the entry is null, or not unlocked
	 */
	public static AKeyPair unlockForUse(Component parent, AWalletEntry entry) {
		boolean[] given={false};
		AKeyPair kp=unlockForUse(entry, ()->{
			char[] password=UnlockWalletDialog.show(parent, entry).getPassPhrase();
			given[0]=(password!=null);
			return password;
		});
		// A password that didn't unlock was wrong; a cancelled dialog needs no message
		if ((kp==null)&&given[0]) {
			JOptionPane.showMessageDialog(parent, "Unable to unlock key pair: wrong password","Unlock Failed",JOptionPane.WARNING_MESSAGE);
		}
		return kp;
	}

	/**
	 * The unlock policy, with the password prompt supplied by the caller. Asks
	 * only if the entry is locked; if a correct password is given, takes the key
	 * pair and locks the entry again.
	 * @param entry Wallet entry (may be null)
	 * @param askPassword Prompt returning a password, or null if declined
	 * @return Key pair, or null if the entry is null, or not unlocked
	 */
	static AKeyPair unlockForUse(AWalletEntry entry, Supplier<char[]> askPassword) {
		if (entry==null) return null;
		if (!entry.isLocked()) return entry.getKeyPair();

		char[] password=askPassword.get();
		if (password==null) return null;
		try {
			synchronized (entry) {
				// Unlocked in the key ring meanwhile, so leave it unlocked
				if (!entry.isLocked()) return entry.getKeyPair();
				if (!entry.tryUnlock(password)) return null;
				try {
					return entry.getKeyPair();
				} finally {
					entry.lock();
				}
			}
		} finally {
			Arrays.fill(password, '\0');
		}
	}

	/**
	 * Finds the key pair for a connection's current account. The account key is
	 * queried off the EDT; if the key ring has a matching entry it is unlocked
	 * for use, on the EDT, asking for its password if needed.
	 * @param parent Parent component for the password dialog
	 * @param convex Connection whose account needs a signer
	 * @return Future completing on the EDT with the signer, or the reason there is none
	 */
	public static CompletableFuture<Signer> signerFor(Component parent, Convex convex) {
		Address address=convex.getAddress();
		if (address==null) {
			return CompletableFuture.completedFuture(Signer.none("No account is selected."));
		}
		return convex.query(Special.forSymbol(Symbols.STAR_KEY), address).handleAsync((r,e)->{
			if (e!=null) return Signer.none("Unable to find the account key: "+e.getMessage());
			if (r.isError()) return Signer.none("Unable to find the account key: "+r.getErrorCode());
			AccountKey key=RT.ensureAccountKey(r.getValue());
			AWalletEntry entry=KeyRing.get().find(key);
			if (entry==null) return Signer.none("The key for this account is not in your key ring.");
			AKeyPair kp=unlockForUse(parent, entry);
			if (kp==null) return Signer.none("The key for this account was not unlocked.");
			return new Signer(kp, null);
		}, Async.EDT);
	}
}
