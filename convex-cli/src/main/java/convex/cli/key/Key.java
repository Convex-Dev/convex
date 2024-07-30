package convex.cli.key;

import convex.cli.ATopCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 *
 * Convex key sub commands
 *
 *		convex.key
 *
 */
@Command(name="key",
	subcommands = {
		KeyImport.class,
		KeyGenerate.class,
		KeyList.class,
		KeyExport.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=false,
	description="Manage local Convex key store.")
public class Key extends ATopCommand {


	@Override
	public void run() {
		// sub command run with no command provided
		showUsage();
	}


}

