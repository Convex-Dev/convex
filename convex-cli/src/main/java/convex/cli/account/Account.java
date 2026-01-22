package convex.cli.account;

import convex.cli.ATopCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;


/**
 * Convex account sub commands
 *
 * convex account
 */
@Command(name="account",
	subcommands = {
		AccountBalance.class,
		AccountCreate.class,
		AccountFund.class,
		AccountInformation.class,
		CommandLine.HelpCommand.class
	},
	description="Manage Convex accounts.")
public class Account extends ATopCommand  {

	@Override
	public void execute() {
		// sub command run with no command provided
		showUsage();
	}
}
