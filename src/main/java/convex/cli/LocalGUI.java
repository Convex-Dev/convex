package convex.cli;

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
@Command(name="gui",
	aliases={},
	mixinStandardHelpOptions=true,
	description="Starts a local convex test network using the peer manager GUI application.")
public class LocalGUI implements Runnable {

	// private static final Logger log = Logger.getLogger(LocalManager.class.getName());

	@ParentCommand
	protected Local localParent;

	@Override
	public void run() {
		Main mainParent = localParent.mainParent;

		// sub command to launch peer manager
		try {
			Applications.launchApp(convex.gui.manager.PeerGUI.class);
		} catch (Throwable t) {
			mainParent.showError(t);
		}
	}
}
