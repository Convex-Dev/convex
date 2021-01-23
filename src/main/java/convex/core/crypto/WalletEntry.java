package convex.core.crypto;

import convex.core.data.AMap;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.exceptions.TODOException;

/**
 * Class implementing a Wallet Entry.
 * 
 * May be in a locked locked or unlocked state. Unlocking requires passphrase.
 */
public class WalletEntry {
	private final Address address;
	private final AKeyPair keyPair;
	private final AMap<Keyword, Object> data;

	private WalletEntry(Address address, AMap<Keyword, Object> data, AKeyPair kp) {
		this.address=address;
		this.data = data;
		this.keyPair = kp;
	}

	private WalletEntry(AMap<Keyword, Object> data) {
		this(null,data, null);
	}

	public static WalletEntry create(Address address,AKeyPair kp) {
		return new WalletEntry(address, Maps.empty(), kp);
	}
	
	public static WalletEntry create(AKeyPair kp) {
		// tODO: Fix
		Address address=Address.create(kp.getAccountKey());
		return new WalletEntry(address, Maps.empty(), kp);
	}

	public AccountKey getAccountKey() {
		return keyPair.getAccountKey();
	}
	
	public Address getAddress() {
		return address;
	}

	public AKeyPair getKeyPair() {
		if (keyPair == null) throw new IllegalStateException("Wallet not unlocked!");
		return keyPair;
	}

	public WalletEntry unlock(char[] password) {
		if (keyPair != null) throw new IllegalStateException("Wallet already unlocked!");

		// byte[] privateKey=PBE.deriveKey(password, data);

		// SignKeyPair kp=SignKeyPair.create(privateKey);
		// return this.withKeyPair(kp);

		throw new TODOException();
	}

	public WalletEntry withKeyPair(AKeyPair kp) {
		// TODO: need an Address in new format
		return new WalletEntry(Address.create(kp.getAccountKey()),data, kp);
	}
	
	public WalletEntry withAddress(Address address) {
		return new WalletEntry(null,data, keyPair);
	}


	public WalletEntry lock() {
		if (keyPair == null) throw new IllegalStateException("Wallet already locked!");
		return this.withKeyPair(null);
	}

	public boolean isLocked() {
		return (keyPair == null);
	}

	@Override
	public String toString() {
		return getAccountKey().toChecksumHex();
	}

	public <R> SignedData<R> sign(R message) {
		return keyPair.signData(message);
	}
}
