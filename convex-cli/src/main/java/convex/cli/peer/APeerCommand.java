package convex.cli.peer;

import convex.cli.ACommand;
import convex.cli.Main;
import etch.EtchStore;
import picocli.CommandLine.ParentCommand;

public abstract class APeerCommand extends ACommand {

	@ParentCommand
	private Peer peerParent;

	@Override
	public Main cli() {
		return peerParent.cli();
	}
	
	public EtchStore getEtchStore() {
		return peerParent.getEtchStore();
	}

}
