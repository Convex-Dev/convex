package convex.cli;

import java.util.HashMap;
import java.util.logging.Logger;
import java.util.Map;

import convex.cli.peer.PeerManager;
import convex.core.data.Keyword;
import convex.core.Init;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/*
	*  convex.account.create command
	*
*/

@Command(name="create",
	mixinStandardHelpOptions=true,
	description="Creates an account using a public/private key from the keystore")
public class AccountCreate implements Runnable {

	private static final Logger log = Logger.getLogger(AccountCreate.class.getName());

	@ParentCommand
	private Local accountParent;


	@Override
	public void run() {

		Main mainParent = accountParent.mainParent;
	}
}
