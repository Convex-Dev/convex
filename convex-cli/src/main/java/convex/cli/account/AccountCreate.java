package convex.cli.account;

import convex.cli.Main;
import convex.core.exceptions.TODOException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 *
 *  Convex account create command
 *
 *  convex.account.create
 *
 */

@Command(name="create",
	mixinStandardHelpOptions=true,
	description="Creates an account on Convex.")
public class AccountCreate extends AAccountCommand {

	@ParentCommand
	private Account accountParent;

	@Option(names={"--new-key"},
		defaultValue="",
		description="Key to use for new account. Should be a valid Ed25519 public key to which the recipient has access.")
	private String keystorePublicKey;

	@Option(names={"-f", "--fund"},
		description="Fund the account with the default fund amount.")
	private boolean isFund;
	
	@Override
	public void execute() {

		throw new TODOException();
	}

	@Override
	public Main cli() {
		return accountParent.cli();
	}
}
