package convex.core.crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.logging.Logger;

import convex.core.data.Address;

public class Wallet {
	public static final String KEYSTORE_TYPE="pkcs12";
	
	private static final Logger log = Logger.getLogger(Wallet.class.getName());

	private HashMap<Address, WalletEntry> data;

	private Wallet(HashMap<Address, WalletEntry> data) {
		this.data = data;
	}

	public static Wallet create() {
		return new Wallet(new HashMap<Address, WalletEntry>());
	}

	public WalletEntry get(Address a) {
		return data.get(a);
	}

	public static File createTempStore(String password) {
		try {
			KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
			char[] pwdArray = "password".toCharArray();
			ks.load(null, pwdArray);
			File file=File.createTempFile("temp-keystore", "p12");
			file.deleteOnExit();
			try (FileOutputStream fos = new FileOutputStream(file)) {
			    ks.store(fos, pwdArray);
			}
			return file;
		} catch (Throwable t) {
			throw new Error("Unable to create temp keystore",t);
		}
	}
	
	public static Wallet load(File file,String password) {
		try {
			KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
			char[] pwdArray = password.toCharArray();
			ks.load(new FileInputStream(file), pwdArray);
			Enumeration<String> aliases=ks.aliases();
			Wallet wallet=Wallet.create();
			
			while (aliases.hasMoreElements()) {
				String alias=aliases.nextElement();
				ks.getKey(alias, pwdArray);
				log.info("Loading private key with alias: "+alias);
			}
			return wallet;
		} catch (Throwable t) {
			throw new Error("Unable to load keystore with file: "+file,t);
		}
	}
}
