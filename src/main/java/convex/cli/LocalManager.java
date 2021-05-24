package convex.cli;

import java.util.logging.Logger;

import convex.api.Applications;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/**
*
* Convex Peer Manager sub command
*
*/
@Command(name="manager",
	mixinStandardHelpOptions=true,
	description="Starts a local convex test network using the peer manager gui application.")
public class LocalManager implements Runnable {

	private static final Logger log = Logger.getLogger(LocalManager.class.getName());

	@ParentCommand
	protected Local localParent;

	@Override
	public void run() {
		// sub command to launch peer manager
		try {
			Applications.launchApp(convex.gui.manager.PeerManager.class);
		} catch (Throwable t) {
			System.err.println("cannot start PeerManager "+t);
		}
	}
}
