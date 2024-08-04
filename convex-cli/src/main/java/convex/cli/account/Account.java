package convex.cli.account;

import convex.cli.ATopCommand;
import convex.cli.Main;
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
		AccountBalance.class,
		AccountCreate.class,
		AccountFund.class,
		AccountInformation.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=false,
	description="Manage Convex accounts.")
public class Account extends ATopCommand  {

	// private static final Logger log = Logger.getLogger(Account.class.getName());

	@ParentCommand
	protected Main mainParent;

	@Override
	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Account(), System.out);
	}

	@Override
	public Main cli() {
		// TODO Auto-generated method stub
		return mainParent.cli();
	}
}
