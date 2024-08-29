package convex.cli.local;

import convex.cli.ATopCommand;
import convex.cli.mixins.EtchMixin;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;


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
	description="Operate a local Convex network and related utilities. Primarily useful for development / testing.")
public class Local extends ATopCommand {

	@Mixin
	protected EtchMixin etchMixin;
	
	@Override
	public void execute() {
		showUsage();
	}
}
