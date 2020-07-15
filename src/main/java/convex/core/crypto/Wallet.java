package convex.core.crypto;

import convex.core.data.AMap;
import convex.core.data.Address;
import convex.core.data.Maps;

public class Wallet {

	private AMap<Address, WalletEntry> data;

	private Wallet(AMap<Address, WalletEntry> data) {
		this.data = data;
	}

	public Wallet create() {
		return new Wallet(Maps.empty());
	}

	public WalletEntry get(Address a) {
		return data.get(a);
	}

}
