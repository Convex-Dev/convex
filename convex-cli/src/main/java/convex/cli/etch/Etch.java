package convex.cli.etch;

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
@Command(name="etch",
	subcommands = {
		EtchDump.class,
		EtchInfo.class,
		EtchRead.class,
		EtchWrite.class,
		EtchValidate.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=false,
	description="Manage an etch database.")
public class Etch extends ATopCommand {

	@Override
	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Etch(), System.out);
	}

}

