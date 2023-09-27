package convex.cli;

import convex.api.Applications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@Command(name="gui",
	aliases={},
	mixinStandardHelpOptions=true,
	description="Starts a local convex test network using the peer manager GUI application.")
public class LocalGUI implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(LocalGUI.class);

	@ParentCommand
	protected Local localParent;

	@Override
	public void run() {
		// Main mainParent = localParent.mainParent;

		log.warn("You will not be able to use some of the CLI 'account' and 'peer' commands.");
		// sub command to launch peer manager
		try {
			Applications.launchApp(convex.gui.PeerGUI.class);
		} catch (Throwable t) {
			throw new CLIError("Error launching GUI",t);
		}
	}
}
