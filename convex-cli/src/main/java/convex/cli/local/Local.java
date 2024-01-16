package convex.cli.local;

import convex.cli.ATopCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;


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
	description="Operate a local Convex network and related utilities.")
public class Local extends ATopCommand {

	@Override
	public void run() {

	}
}
