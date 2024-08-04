package convex.cli.local;

import convex.api.Applications;
import convex.cli.CLIError;
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
public class LocalGUI extends ALocalCommand {

	// private static final Logger log = LoggerFactory.getLogger(LocalGUI.class);

	@ParentCommand
	protected Local localParent;

	@Override
	public void run() {
		// sub command to launch peer manager
		try {
			Applications.launchApp(convex.gui.peer.PeerGUI.class);
		} catch (Throwable t) {
			throw new CLIError("Error launching GUI: ",t);
		}
	}

}
