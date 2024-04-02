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
	 * Gets the key pair associated with this wallet entry
	 * @return
	 */
	AKeyPair getKeyPair();
	
	/**
	 * Get the public key associated with this wallet entry
	 * @return
	 */
	AccountKey  getPublicKey();

}
