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
* Convex peer sub commands
*
*/
@Command(name="local",
	subcommands = {
		LocalManager.class,
		LocalStart.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=true,
	description="Operates a local convex network.")
public class Local implements Runnable {

	private static final Logger log = Logger.getLogger(Local.class.getName());

	static public List<Server> peerServerList = new ArrayList<Server>();

	@ParentCommand
	protected Main mainParent;

	@Override
	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Local(), System.out);
	}
}
