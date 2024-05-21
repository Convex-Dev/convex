package convex.core.crypto.wallet;

import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;

public interface IWalletEntry {
	
	/**
	 * Check if this wallet entry is locked.
	 * @return True if wallet is locked
	 */
	public boolean isLocked();

	/**
	 * Gets the key pair associated with this wallet entry, if unlocked
	 * @return Key pair instance, or null if not available (locked)
	 */
	AKeyPair getKeyPair();
	
	/**
	 * Get the public key associated with this wallet entry
	 * @return Public Key
	 */
	AccountKey getPublicKey();
	
	/**
	 * Try to unlock a wallet with the given password
	 * @param password Password to unlock
	 * 
	 * @return true if unlocked, false otherwise
	 */
	public boolean tryUnlock(char[] password);

	/**
	 * Unlock the wallet entry. Unlocking makes the entry usable for signing
	 * @param password Unlock password
	 */
	public void unlock(char[] password);

	/**
	 * Lock the wallet entry. Locking makes the wallet entry unusable until unlocked.
	 * @param password Password with which to lock wallet
	 */
	public void lock(char[] password);

}
