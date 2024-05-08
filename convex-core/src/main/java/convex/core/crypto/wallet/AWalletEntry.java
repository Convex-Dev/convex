package convex.core.crypto.wallet;

import convex.core.data.AArrayBlob;

public abstract class AWalletEntry implements IWalletEntry {

	@Override
	public void unlock(char[] password) {
		if (tryUnlock(password)) return;

		throw new IllegalStateException("Invalid password");
	}

	/**
	 * Returns the data to be used for a wallet identicon. Should be the public account key
	 * @return Data to be used for an identicon
	 */
	public abstract AArrayBlob getIdenticonData();

}
