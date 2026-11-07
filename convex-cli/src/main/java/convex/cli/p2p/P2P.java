package convex.cli.p2p;

import convex.cli.ATopCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * P2P sub commands for running a peer-to-peer lattice node.
 *
 *		convex p2p
 */
@Command(name="p2p",
	subcommands = {
		P2PStart.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=true,
	description="Operate a P2P lattice node.")
public class P2P extends ATopCommand {

	@Override
	public void execute() {
		showUsage();
	}
}
