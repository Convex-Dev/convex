package convex.cli.key;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.Enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
import convex.cli.ExitCodes;
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
	public void execute() {
		KeyStore keyStore = storeMixin.loadKeyStore();
		if (keyStore==null) throw new CLIError(ExitCodes.NOINPUT,"Keystore does not exist. Specify a valid --keystore or use `convex key gen` to create one.");
		
		Enumeration<String> aliases;
		int n=0;
		try {
			aliases = keyStore.aliases();
			while (aliases.hasMoreElements()) {
				String alias = aliases.nextElement();
				println(alias);
				n++;
			}
		} catch (KeyStoreException e) {
			throw new CLIError("Unexpected error reading keystore",e);
		}
		if (n==0) {
			this.inform("Keystore contains no keys");		
		} else {
			this.inform(3, n+" key(s) found");
		}
	}
}
