package convex.cli;

import java.io.File;
import java.security.KeyStore;
import java.util.Enumeration;

import convex.core.crypto.PFXTools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger log = LoggerFactory.getLogger(KeyList.class);

	@ParentCommand
	protected Key keyParent;

	@Override
	public void run() {
		Main mainParent = keyParent.mainParent;

		String password = mainParent.getPassword();
		if (password == null) {
			log.warn("You need to provide a keystore password");
			return;
		}
		File keyFile = new File(mainParent.getKeyStoreFilename());
		try {
			if (!keyFile.exists()) {
				log.error("Cannot find keystore file {}", keyFile.getCanonicalPath());
			}
			KeyStore keyStore = PFXTools.loadStore(keyFile, password);
			Enumeration<String> aliases = keyStore.aliases();
			int index = 1;
			while (aliases.hasMoreElements()) {
				String alias = aliases.nextElement();
				mainParent.output.setField("Index", String.format("%5d", index));
				mainParent.output.setField("Public Key", alias);
				mainParent.output.addRow();
				index ++;
			}

		} catch (Throwable t) {
			mainParent.showError(t);
		}
	}

}
