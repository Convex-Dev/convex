package convex.core.crypto;

import convex.core.data.ACell;
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
	private final AMap<Keyword, ACell> data;

	private WalletEntry(Address address, AMap<Keyword, ACell> data, AKeyPair kp) {
		this.address=address;
		this.data = data;
		this.keyPair = kp;
	}

	private WalletEntry(AMap<Keyword, ACell> data) {
		this(null,data, null);
	}

	public static WalletEntry create(Address address,AKeyPair kp) {
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
		return new WalletEntry(address,data, kp);
	}
	
	public WalletEntry withAddress(Address address) {
		return new WalletEntry(null,data, keyPair);
	}

	public WalletEntry lock() {
		if (keyPair == null) throw new IllegalStateException("Wallet already locked!");
		// Clear keypair
		return this.withKeyPair(null);
	}

	public boolean isLocked() {
		return (keyPair == null);
	}

	@Override
	public String toString() {
		return getAddress() +" : " +getAccountKey().toChecksumHex();
	}

	public <R extends ACell> SignedData<R> sign(R message) {
		return keyPair.signData(message);
	}
}
