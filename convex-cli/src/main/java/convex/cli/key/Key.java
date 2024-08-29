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
		KeySign.class,
		KeyExport.class,
		KeyDelete.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=false,
	description="Manage keys in a local Convex PKCS #12 key store.")
public class Key extends ATopCommand {


	@Override
	public void execute() {
		// sub command run with no command provided
		showUsage();
	}


}

