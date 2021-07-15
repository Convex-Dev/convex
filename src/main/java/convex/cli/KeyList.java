package convex.cli;

import java.io.File;
import java.security.KeyStore;
import java.util.Enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.PFXTools;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/**
 *
 * Convex key sub commands
 *
 *		convex.key.list
 *
 *
 */
@Command(name="list",
	aliases={"li"},
	mixinStandardHelpOptions=true,
	description="List available key pairs.")
public class KeyList implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(KeyList.class.getName());

	@ParentCommand
	protected Key keyParent;

	@Override
	public void run() {
		Main mainParent = keyParent.mainParent;

		String password = mainParent.getPassword();
		if (password == null) {
			System.out.println("You need to provide a keystore password");
			return;
		}
		File keyFile = new File(mainParent.getKeyStoreFilename());
		try {
			if (!keyFile.exists()) {
				System.out.println("Cannot find keystore file: "+ keyFile.getCanonicalPath());
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
			log.error("Cannot load key store "+t);
		}

	}

}
