package convex.cli;

import java.io.IOException;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.Map;


import convex.api.Shutdown;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.crypto.AKeyPair;
import convex.core.store.AStore;
import convex.core.Init;
import convex.core.store.Stores;
import convex.core.Order;
import convex.core.State;
import convex.peer.API;
import convex.peer.Server;
import etch.EtchStore;
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
