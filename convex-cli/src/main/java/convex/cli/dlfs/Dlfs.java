package convex.cli.dlfs;

import convex.cli.ATopCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * DLFS sub commands for serving a replicated Data Lattice File System.
 *
 *		convex dlfs
 */
@Command(name="dlfs",
	subcommands = {
		DlfsStart.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=true,
	description="Operate a DLFS (Data Lattice File System) server with lattice replication.")
public class Dlfs extends ATopCommand {

	@Override
	public void execute() {
		showUsage();
	}
}
