package convex.cli.key;

import convex.cli.ACommand;
import convex.cli.Main;
import convex.cli.mixins.KeyMixin;
import convex.cli.mixins.StoreMixin;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

/**
 * Base class for commands working with the configured key store
 */
public abstract class AKeyCommand extends ACommand {

	@ParentCommand
	protected Key keyParent;
	
	@Mixin
	protected StoreMixin storeMixin; 
	
	@Mixin
	protected KeyMixin keyMixin;

	
	@Override
	public Main cli() {
		return keyParent.cli();
	}
}
