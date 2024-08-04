package convex.cli.local;

import convex.cli.ACommand;
import convex.cli.Main;
import convex.cli.mixins.KeyMixin;
import convex.cli.mixins.StoreMixin;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

public abstract class ALocalCommand extends ACommand {

	@Mixin
	protected StoreMixin storeMixin; 

	@Mixin
	protected KeyMixin keyMixin; 

	
	@ParentCommand
	private ACommand parent;
	
	@Override
	public Main cli() {
		return parent.cli();
	}

}
