package convex.cli.peer;

import convex.cli.Helpers;
import convex.cli.Main;
import picocli.CommandLine.ParentCommand;

public abstract class APeerCommand implements Runnable {

	@ParentCommand
	private Peer peerParent;
	
	public String getEtchStoreFilename() {
		if ( peerParent.etchStoreFilename != null) {
			return Helpers.expandTilde(peerParent.etchStoreFilename).strip();
		}
		return null;
	}

	protected Main cli() {
		return peerParent.cli();
	}
}
