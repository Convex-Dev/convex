package convex.cli.peer;

import convex.cli.Main;
import etch.EtchStore;
import picocli.CommandLine.ParentCommand;

public abstract class APeerCommand implements Runnable {

	@ParentCommand
	private Peer peerParent;

	protected Main cli() {
		return peerParent.cli();
	}
	
	public EtchStore getEtchStore() {
		return peerParent.getEtchStore();
	}

}
