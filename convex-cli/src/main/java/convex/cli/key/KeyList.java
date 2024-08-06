package convex.cli.key;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.Enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
import picocli.CommandLine.Command;

/**
 *
 * Convex key sub commands
 *
 *		convex.key.list
 *
 *
 */
@Command(name="list",
	mixinStandardHelpOptions=true,
	description="List available key pairs.")
public class KeyList extends AKeyCommand {
	static final Logger log = LoggerFactory.getLogger(KeyList.class);

	@Override
	public void run() {
		KeyStore keyStore = storeMixin.loadKeyStore();
		if (keyStore==null) throw new CLIError("Keystore does not exist. Specify a valid --keystore or use `convex key gen` to create one.");
		
		Enumeration<String> aliases;
		try {
			aliases = keyStore.aliases();
			while (aliases.hasMoreElements()) {
				String alias = aliases.nextElement();
				println(alias);
			}
		} catch (KeyStoreException e) {
			throw new CLIError("Unexpected error reading keystore",e);
		}
	}
}
