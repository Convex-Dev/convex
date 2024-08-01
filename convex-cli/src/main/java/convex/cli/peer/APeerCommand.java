package convex.cli.peer;

import convex.cli.ACommand;
import convex.cli.Main;
import convex.cli.mixins.EtchMixin;
import etch.EtchStore;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

public abstract class APeerCommand extends ACommand {

	@ParentCommand
	private Peer peerParent;
	
	@Mixin
    protected EtchMixin  etchMixin;

	@Override
	public Main cli() {
		return peerParent.cli();
	}

	public EtchStore getEtchStore() {
		return etchMixin.getEtchStore();
	}
}
