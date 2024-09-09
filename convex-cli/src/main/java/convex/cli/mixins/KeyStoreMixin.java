package convex.cli.mixins;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.util.Enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
import convex.cli.Constants;
import convex.cli.ExitCodes;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.util.FileUtils;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

public class KeyStoreMixin extends AMixin {

	/**
	 * Keystore option
	 */
	@Option(names = { "--keystore" }, 
			defaultValue = "${env:CONVEX_KEYSTORE:-" + Constants.KEYSTORE_FILENAME+ "}", 
			scope = ScopeType.INHERIT, 
			description = "Keystore filename. Can specify with CONVEX_KEYSTORE. Default: ${DEFAULT-VALUE}")
	private String keyStoreFilename;

	/**
	 * Password for keystore. Option named to match Java keytool
	 */
	@Option(names = {"--storepass" }, 
			scope = ScopeType.INHERIT, 
			defaultValue = "${env:CONVEX_KEYSTORE_PASSWORD}", 
			arity="0..1",
			description = "Store integrity password for the keystore.") 
	char[] keystorePassword;

	KeyStore keyStore = null;
	
	static Logger log = LoggerFactory.getLogger(KeyStoreMixin.class);

	/**
	 * Gets the keystore file name currently specified for the CLI
	 * 
	 * @return File name, or null if not specified
	 */
	private File getKeyStoreFile() {
		if (keyStoreFilename != null) {
			File f = FileUtils.getFile(keyStoreFilename);
			return f;
		}
		return null;
	}
	
	public Path getStorePath() {
		return getKeyStoreFile().toPath();
	}

	/**
	 * Get the currently configured password for the keystore.
	 * 
	 * @return Password, or null if unspecified
	 */
	public char[] getStorePassword() {
		if (keystorePassword != null) return keystorePassword;	
		else if (isParanoid()) {
			// enforce integrity check in strict mode
			if (isInteractive()) {
				keystorePassword = readPassword("Enter keystore integrity password: ");
			} else {
				paranoia("Keystore integrity password must be explicitly provided");
			}
		} 
		return keystorePassword;
	}

	/**
	 * Gets the current key store
	 * 
	 * @return KeyStore instance, or null if it is not loaded
	 */
	public KeyStore getKeystore() {
		return keyStore;
	}

	/**
	 * Loads the currently specified key Store, creating it if it does not exist
	 * 
	 * @return KeyStore instance
	 */
	public KeyStore ensureKeyStore() {
		if (keyStore!=null) return keyStore;
		return loadKeyStore(true, getStorePassword());
	}
	
	/**
	 * Loads the currently specified key Store. Does not create
	 * 
	 * @return KeyStore instance, or null if it does not exist
	 */
	public KeyStore loadKeyStore() {
		if (keyStore!=null) return keyStore;
		return loadKeyStore(false, getStorePassword());
	}

	/**
	 * Loads the currently configured key Store
	 * 
	 * @param shouldCreate Flag to indicate if keystore should be created if absent
	 * @param password Key store password
	 * @return KeyStore instance, or null if does not exist
	 */
	private KeyStore loadKeyStore(boolean shouldCreate, char[] password) {
		File keyFile = getKeyStoreFile();
		try {
			if (keyFile.exists()) {
				this.inform(3, "Loading key store at: "+keyFile);
				keyStore = PFXTools.loadStore(keyFile, password);
			} else if (shouldCreate) {
				informWarning("No keystore exists, creating at: " + keyFile.getCanonicalPath());
				FileUtils.ensureFilePath(keyFile);
				keyStore = PFXTools.createStore(keyFile, password);
			} else {
				keyStore=null;
			}
		}  catch (FileNotFoundException e) {
			return null;
		} catch (GeneralSecurityException e) {
			throw new CLIError("Unexpected security error: " + e.getClass(), e);
		} catch (IOException e) {
			if (e.getCause() instanceof UnrecoverableKeyException) {
				throw new CLIError(ExitCodes.NOPERM,"Integrity password check failed for keystore: " + keyFile, e.getCause());
			}
			
			throw new CLIError("Unable to load keystore due to unexpected IO Error: " + keyFile, e);
		}
		return keyStore;
	}
	
	public void saveKeyStore() {
		saveKeyStore(keystorePassword);
	}

	public void saveKeyStore(char[] storePassword) {
		// save the keystore file
		if (keyStore == null)
			throw new CLIError("Trying to save a keystore that has not been loaded!");
		try {
			if (keystorePassword==null) {
				paranoia("Trying to save keystore in strict mode with no integrity password");
			}
			PFXTools.saveStore(keyStore, getKeyStoreFile(), storePassword);
		} catch (IOException | GeneralSecurityException t) {
			throw new CLIError("Failed to save keystore",t);
		}
	}

	/**
	 * Adds key pair to store. Does not save keystore!
	 * 
	 * @param keyPair Keypair to add
	 * @param keyPassword PAssword for new key
	 */
	public void addKeyPairToStore(AKeyPair keyPair, char[] keyPassword) {
	
		KeyStore keyStore = getKeystore();
		if (keyStore == null) {
			throw new CLIError("Trying to add key pair but keystore is not yet loaded!");
		}
		try {
			// save the key in the keystore
			PFXTools.setKeyPair(keyStore, keyPair, keyPassword);
		} catch (IOException | GeneralSecurityException t) {
			throw new CLIError("Cannot store the key to the key store",t);
		}
	
	}
	
	public static String trimKey(String publicKey) {
		publicKey = publicKey.trim();

		publicKey = publicKey.toLowerCase().replaceFirst("^0x", "").strip();
		if (publicKey.isEmpty()) {
			return null;
		}
		return publicKey;
	}

	/**
	 * Loads a keypair from configured keystore
	 * 
	 * @param publicKey String identifying the public key. May be a prefix
	 * @return Keypair instance, or null if not found
	 */
	public AKeyPair loadKeyFromStore(String publicKey, char[] keyPassword) {
		if (publicKey == null)
			return null;
	
		publicKey = trimKey(publicKey);
		if (publicKey==null) return null;		

		try {
			KeyStore keyStore = ensureKeyStore();
	
			Enumeration<String> aliases = keyStore.aliases();
			while (aliases.hasMoreElements()) {
				String alias = aliases.nextElement();
				if (alias.indexOf(publicKey) == 0) {
					log.trace("found keypair " + alias);
					return PFXTools.getKeyPair(keyStore, alias, keyPassword);
				}
			}
			return null;
		} catch (UnrecoverableKeyException t) {
			throw new CLIError(ExitCodes.CONFIG,"Cannot load key from key Store - possibly incorrect password?", t);
		} catch (GeneralSecurityException t) {
			throw new CLIError("Cannot load key from key Store", t);
		}
	}

	public boolean hasSingleKey(String pk) {
		return keyCount(pk)==1;
	}

	public int keyCount(String pk) {
		pk=trimKey(pk);
		
		int result=0;
		try {
			KeyStore keyStore = ensureKeyStore();
	
			Enumeration<String> aliases = keyStore.aliases();
			while (aliases.hasMoreElements()) {
				String alias = aliases.nextElement();
				if (alias.indexOf(pk) == 0) {
					result++;
				}
			}
		} catch (GeneralSecurityException t) {
			throw new CLIError("Cannot load aliases from key Store", t);
		}
		return result;
	}



}
