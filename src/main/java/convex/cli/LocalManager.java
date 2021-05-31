package convex.cli;

import java.util.logging.Logger;

import convex.api.Applications;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/**
 *
 * Convex Local Manager sub command
 *
 *		convex.local.manager
 *
 *
 */
@Command(name="manager",
	aliases={"ma"},
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
			System.err.println("cannot start local PeerManager "+t);
		}
	}
}
