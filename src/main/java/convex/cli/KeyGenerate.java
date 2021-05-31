package convex.cli;

import java.io.File;
import java.util.logging.Logger;
import java.security.KeyStore;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;


/**
 *
 * Convex key sub commands
 *
 *		convex.key.generate
 *
 *
 */
@Command(name="generate",
	aliases={"ge"},
	mixinStandardHelpOptions=true,
	description="Generate 1 or more private key pairs.")
public class KeyGenerate implements Runnable {

	private static final Logger log = Logger.getLogger(KeyGenerate.class.getName());

	@ParentCommand
	protected Key keyParent;


	@Parameters(paramLabel="count",
		defaultValue="" + Constants.KEY_GENERATE_COUNT,
		description="Number of keys to generate. Default: ${DEFAULT-VALUE}")
	private int count;

	@Override
	public void run() {
		// sub command to generate keys
		Main mainParent = keyParent.mainParent;
		// check the number of keys to generate.
		if (count <= 0) {
			log.severe("You to provide 1 or more count of keys to generate");
			return;
		}
		log.info("will generate "+count+" keys");
		// get the password of the key store file
		String password = mainParent.getPassword();
		if (password == null) {
			log.severe("You need to provide a keystore password");
			return;
		}
        // get the key store file
		File keyFile = new File(mainParent.getKeyStoreFilename());

		KeyStore keyStore = null;
		try {
            // try to load the keystore file
			if (keyFile.exists()) {
				keyStore = PFXTools.loadStore(keyFile, password);
			} else {
				// create the path to the new key file
				Helpers.createPath(keyFile);
				keyStore = PFXTools.createStore(keyFile, password);
			}
		} catch (Throwable t) {
			System.out.println("Cannot load key store "+t);
		}

        // we have now the count, keystore-password, keystore-file
        // generate keys
		for (int index = 0; index < count; index ++) {
			AKeyPair keyPair = AKeyPair.generate();

			System.out.println("generated #"+(index+1)+" public key: " + keyPair.getAccountKey().toHexString());
			try {
                // save the key in the keystore
				PFXTools.saveKey(keyStore, keyPair, password);
			} catch (Throwable t) {
				System.out.println("Cannot store the key to the key store "+t);
			}
		}

        // save the keystore file
		try {
			PFXTools.saveStore(keyStore, keyFile, password);
		} catch (Throwable t) {
			System.out.println("Cannot save the key store file "+t);
		}
	}
}
