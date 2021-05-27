package convex.cli;

import java.util.logging.Logger;


import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;


/**
 *
 * Convex account sub commands
 *
 *		convex.account
 *
 */
@Command(name="account",
	subcommands = {
		AccountCreate.class,
		AccountFund.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=true,
	description="Manages convex accounts.")
public class Account implements Runnable {

	private static final Logger log = Logger.getLogger(Account.class.getName());

	@ParentCommand
	protected Main mainParent;

	@Override
	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Account(), System.out);
	}
}
