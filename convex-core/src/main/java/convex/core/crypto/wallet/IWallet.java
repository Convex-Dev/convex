package convex.core.crypto.wallet;

import convex.core.data.AccountKey;

public interface IWallet {

	public void getKeyPair(AccountKey pubKey);
}
