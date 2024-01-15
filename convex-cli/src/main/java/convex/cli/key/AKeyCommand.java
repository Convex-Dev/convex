package convex.cli.key;

import java.security.KeyStore;

import convex.cli.Main;
import picocli.CommandLine.ParentCommand;

/**
 * Base class for commands working with the configured key store
 */
public abstract class AKeyCommand implements Runnable {

	@ParentCommand
	protected Key keyParent;
	
	protected Main cli() {
		return keyParent.cli();
	}
	
	protected KeyStore loadKeyStore(boolean create) {
		return cli().loadKeyStore(create);
	}
	
	protected void saveKeyStore() {
		cli().saveKeyStore();
	}
	
	
}
