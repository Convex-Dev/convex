package convex.cli.peer;

import convex.cli.Main;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;


/**
 *
 * Convex peer sub commands
 *
 *		convex.peer
 *
 */
@Command(name="peer",
	subcommands = {
		PeerCreate.class,
		PeerStart.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=true,
	description="Operates a local peer.")
public class Peer implements Runnable {

	// private static final Logger log = Logger.getLogger(Peer.class.getName());

	@ParentCommand
	protected Main mainParent;

	@Override
	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Peer(), System.out);
	}

}
