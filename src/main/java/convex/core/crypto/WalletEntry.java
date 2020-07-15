package convex.core.crypto;

import convex.core.data.AMap;
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
	private final AKeyPair keyPair;
	private final AMap<Keyword, Object> data;

	private WalletEntry(AMap<Keyword, Object> data, AKeyPair heroKp) {
		this.data = data;
		this.keyPair = heroKp;
	}

	private WalletEntry(AMap<Keyword, Object> data) {
		this(data, null);
	}

	public static WalletEntry create(AKeyPair heroKp) {
		return new WalletEntry(Maps.empty(), heroKp);
	}

	public Address getAddress() {
		return keyPair.getAddress();
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

	private WalletEntry withKeyPair(AKeyPair kp) {
		return new WalletEntry(data, kp);
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
		return getAddress().toChecksumHex();
	}

	public <R> SignedData<R> sign(R message) {
		return keyPair.signData(message);
	}
}
