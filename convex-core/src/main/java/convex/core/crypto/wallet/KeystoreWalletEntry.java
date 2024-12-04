package convex.core.crypto.wallet;

import java.security.GeneralSecurityException;
import java.security.KeyStore;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.data.AArrayBlob;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;

/**
 * Class implementing a Hot Wallet Entry. Hot keys are stored in memory, and are accessible to
 * attackers able to gain full control of the machine. 
 * 
 * May be in a locked locked or unlocked state. Unlocking requires passphrase.
 */
public class KeystoreWalletEntry extends AWalletEntry {
	private final KeyStore ks;
	private AKeyPair keyPair=null;
	private String alias=null;
	private AccountKey key=null;

	public KeystoreWalletEntry(KeyStore ks, String alias, String source) {
		super (source);
		this.ks=ks;
		this.alias=alias;
		this.key=AccountKey.parse(alias);;
	}

	public static KeystoreWalletEntry create(KeyStore ks, String alias, String source) {
		return new KeystoreWalletEntry(ks,alias,source);
	}

	@Override
	public AccountKey getPublicKey() {
		return key;
	}

	@Override
	public synchronized AKeyPair getKeyPair() {
		// Note: null if locked
		return keyPair;
	}


	@Override
	public boolean isLocked() {
		return keyPair==null;
	}

	@Override
	public String toString() {
		AccountKey pubKey=getPublicKey(); 
		String ks="0x"+pubKey.toChecksumHex();
		return "Wallet Entry for: "+ks;
	}

	public <R extends ACell> SignedData<R> sign(R message) {
		return keyPair.signData(message);
	}

	@Override
	public AArrayBlob getIdenticonData() {
		return key;
	}

	@Override
	public synchronized boolean tryUnlock(char[] password) {
		if (!isLocked()) return true;
		try {
			keyPair=PFXTools.getKeyPair(ks, alias, password);
			return (keyPair!=null);
		} catch (GeneralSecurityException e) {
			return false;
		}
	}
	
	@Override
	public synchronized void lock(char[] password) {
		keyPair=null;
	}

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public boolean needsLockPassword() {
		return false;
	}

	@Override
	public void lock() {
		keyPair=null;
	}


}
