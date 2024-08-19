package convex.core.crypto.wallet;

import convex.core.crypto.AKeyPair;
import convex.core.data.AArrayBlob;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.Strings;

/**
 * Class implementing a Hot Wallet Entry. Hot keys are stored in memory, and are accessible to
 * attackers able to gain full control of the machine. 
 * 
 * May be in a locked locked or unlocked state. Unlocking requires passphrase.
 */
public class HotWalletEntry extends AWalletEntry {
	private final AKeyPair keyPair;
	private Hash passHash=null;
	boolean locked=false;
	private String source;

	public HotWalletEntry(AKeyPair kp, String source) {
		super (source);
		this.keyPair = kp;
		this.source=source;
	}

	public static HotWalletEntry create(AKeyPair kp,String source) {
		return new HotWalletEntry(kp,source);
	}

	@Override
	public AccountKey getPublicKey() {
		if (keyPair==null) return null;
		return keyPair.getAccountKey();
	}

	@Override
	public synchronized AKeyPair getKeyPair() {
		if (isLocked()) throw new IllegalStateException("Wallet not unlocked!");
		return keyPair;
	}


	@Override
	public boolean isLocked() {
		return locked;
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
		return keyPair.getAccountKey();
	}

	@Override
	public synchronized boolean tryUnlock(char[] password) {
		if (!isLocked()) return true;
		Hash h=getPasswordHash(password);
		if (h.equals(passHash)) {
			locked=false;
			return true;
		} else {
			return false;
		}
	}
	
	@Override
	public synchronized void lock(char[] password) {
		Hash h=getPasswordHash(password);
		if (locked) {
			throw new IllegalStateException("Wallet already locked)");
		} else {
			passHash=h;
			locked=true;
		}
	}

	protected Hash getPasswordHash(char[] password) {
		String s=new String(password);
		Hash h=Strings.create(s).getHash();
		return h;
	}

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public boolean needsLockPassword() {
		return passHash==null;
	}

	@Override
	public void lock() {
		locked=true;
	}

}
