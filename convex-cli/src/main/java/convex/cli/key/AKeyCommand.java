package convex.cli.key;

import convex.cli.ACommand;
import convex.cli.Main;
import convex.cli.mixins.KeyStoreMixin;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

/**
 * Base class for commands working with the configured key store
 */
public abstract class AKeyCommand extends ACommand {

	@ParentCommand
	protected Key keyParent;
	
	@Mixin
	protected KeyStoreMixin storeMixin; 

	
	@Override
	public Main cli() {
		return keyParent.cli();
	}
}
