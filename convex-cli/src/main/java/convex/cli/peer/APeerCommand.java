package convex.cli.peer;

import convex.cli.Main;
import picocli.CommandLine.ParentCommand;

public abstract class APeerCommand implements Runnable {

	@ParentCommand
	private Peer peerParent;

	protected Main cli() {
		return peerParent.cli();
	}
}
