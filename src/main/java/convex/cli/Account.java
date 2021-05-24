package convex.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;


import convex.peer.Server;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;


/**
*
* Convex account sub commands
*
*/
@Command(name="account",
	subcommands = {
		AccountCreate.class,
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
