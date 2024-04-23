package convex.core.crypto.wallet;

import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;

public interface IWalletEntry {
	
	/**
	 * Check if this wallet entry is locked.
	 * @return
	 */
	public boolean isLocked();

	/**
	 * Gets the key pair associated with this wallet entry, if unlocked
	 * @return Key pair instance, or null if not available (locked)
	 */
	AKeyPair getKeyPair();
	
	/**
	 * Get the public key associated with this wallet entry
	 * @return
	 */
	AccountKey getPublicKey();
	
	/**
	 * Try to unlock a wallet with the given password
	 * 
	 * @return true if unlocked, false otherwise
	 */
	public boolean tryUnlock(char[] password);

	/**
	 * Unlock the wallet entry. Unlocking makes the entry usable for signing
	 * @param passPhrase
	 * @return 
	 */
	public void unlock(char[] passPhrase);

	/**
	 * Lock the wallet entry. Locking makes the wallet entry unusable until unlocked.
	 * @param password
	 */
	public void lock(char[] password);

}
