package convex.cli;

import java.io.File;
import java.security.KeyStore;
import java.util.Enumeration;
import java.util.logging.Logger;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/**
*
* Convex key sub commands
*
*/
@Command(name="list",
	mixinStandardHelpOptions=true,
	description="List available key pairs.")
public class KeyList implements Runnable {

	private static final Logger log = Logger.getLogger(KeyList.class.getName());

	@ParentCommand
	protected Key keyParent;

	@Override
	public void run() {

		String password = keyParent.getPassword();
		if (password == null) {
			log.severe("You need to provide a keystore password");
			return;
		}
		File keyFile = new File(keyParent.getKeyStoreFilename());
		try {
			if (!keyFile.exists()) {
				log.severe("Cannot find keystore file "+keyFile.getCanonicalPath());
			}
			KeyStore keyStore = PFXTools.loadStore(keyFile, password);
			Enumeration<String> aliases = keyStore.aliases();
			int index = 1;
			while (aliases.hasMoreElements()) {
				String alias = aliases.nextElement();
				System.out.println("#"+index+" Public Key: "+alias);
				index ++;
			}

		} catch (Throwable t) {
			System.out.println("Cannot load key store "+t);
		}

	}

}
