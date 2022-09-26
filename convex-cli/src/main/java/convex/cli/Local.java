package convex.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;


/**
 *
 * Convex local sub commands
 *
 *		convex.local
 *
 *
 */
@Command(name="local",
	aliases={},
	subcommands = {
		LocalGUI.class,
		LocalStart.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=true,
	description="Operates a local convex network.")
public class Local implements Runnable {

	@ParentCommand
	protected Main mainParent;

	@Override
	public void run() {
		// run with no command provided
		CommandLine.usage(new Local(), System.out);
	}
}
