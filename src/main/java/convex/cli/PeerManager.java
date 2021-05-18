package convex.cli;

import java.io.IOException;
import java.lang.Exception;
import java.util.logging.Logger;

import convex.api.Applications;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
*
* Convex Peer Manager sub command
*
*/
@Command(name="manager",
	mixinStandardHelpOptions=true,
	description="Launch the peer manager gui.")
public class PeerManager implements Runnable {

	private static final Logger log = Logger.getLogger(PeerManager.class.getName());

	@ParentCommand
	protected Peer peerParent;

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
