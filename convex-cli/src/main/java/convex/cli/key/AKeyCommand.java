package convex.cli.key;

import convex.cli.ACommand;
import convex.cli.Main;
import picocli.CommandLine.ParentCommand;

/**
 * Base class for commands working with the configured key store
 */
public abstract class AKeyCommand extends ACommand {

	@ParentCommand
	protected Key keyParent;
	
	@Override
	public Main cli() {
		return keyParent.cli();
	}
}
