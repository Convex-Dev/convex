package convex.core.crypto.wallet;

import convex.core.data.AArrayBlob;

public abstract class AWalletEntry implements IWalletEntry {

	protected final String source;

	public AWalletEntry(String source) {
		this.source=source;
	}

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

	public String getSource() {
		return source;
	}

	/**
	 * Checks if this wallet entry needs a password to lock
	 * @return True if wallet needs a password to unlock
	 */
	public abstract boolean needsLockPassword();

	public abstract void lock();

}
