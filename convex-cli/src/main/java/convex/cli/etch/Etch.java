package convex.cli.etch;

import convex.cli.ATopCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 *
 * Convex etch sub commands
 *
 *		convex.etch
 *
 */
@Command(name="etch",
	subcommands = {
		EtchDump.class,
		EtchInfo.class,
		EtchRead.class,
		EtchWrite.class,
		EtchClear.class,
		EtchValidate.class,
		EtchGC.class,
		EtchMigrate.class,
		EtchRecover.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=true,
	description="Manage an etch database.")
public class Etch extends ATopCommand {

	@Override
	public void execute() {
		showUsage();
	}

}

