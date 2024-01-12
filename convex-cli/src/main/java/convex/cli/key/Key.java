package convex.cli.key;

import convex.cli.Main;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

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
	mixinStandardHelpOptions=true,
	description="Manage local Convex key store.")
public class Key implements Runnable {

	@ParentCommand
	protected Main mainParent;

	@Override
	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Key(), System.out);
	}
}

