package convex.cli.etch;

import convex.cli.ACommand;
import convex.cli.Main;
import convex.cli.mixins.EtchMixin;
import convex.etch.EtchStore;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

public abstract class AEtchCommand extends ACommand {
	
	@ParentCommand
	protected Etch etchParent;

	@Mixin
    protected EtchMixin etchMixin;

	
	@Override
	public Main cli() {
		return etchParent.cli();
	}
	
	public EtchStore store() {
		return etchMixin.getEtchStore();
	}

}
