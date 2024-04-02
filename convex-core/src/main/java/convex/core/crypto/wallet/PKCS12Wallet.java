package convex.core.crypto.wallet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.util.Enumeration;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.AccountKey;
import convex.core.data.Address;

public class PKCS12Wallet extends AWallet {
	public static final String KEYSTORE_TYPE="pkcs12";
	
	private static final Logger log = LoggerFactory.getLogger(PKCS12Wallet.class.getName());

	private HashMap<Address, BasicWalletEntry> data;

	private PKCS12Wallet(HashMap<Address, BasicWalletEntry> data) {
		this.data = data;
	}

	public static PKCS12Wallet create() {
		return new PKCS12Wallet(new HashMap<Address, BasicWalletEntry>());
	}

	public BasicWalletEntry get(Address a) {
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
	
	public static PKCS12Wallet load(File file,String password) {
		try {
			KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
			char[] pwdArray = password.toCharArray();
			ks.load(new FileInputStream(file), pwdArray);
			Enumeration<String> aliases=ks.aliases();
			PKCS12Wallet wallet=PKCS12Wallet.create();
			
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

	@Override
	public void getKeyPair(AccountKey pubKey) {
		// TODO Auto-generated method stub
		
	}
}
