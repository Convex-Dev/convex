package convex.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 *
 * Convex Transact sub command
 *
 *		convex.transact
 *
 */
@Command(name="transact",
	mixinStandardHelpOptions=true,
	description="Execute a transaction on the network via the current peer.")
public class Transact implements Runnable {

	@ParentCommand
	protected Main mainParent;

	@Option(names={"--port"},
		description="Port number to connect or create a peer.")
	private int port = 0;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname to connect to a peer. Default: ${DEFAULT-VALUE}")
	private String hostname;

	@Override
	public void run() {
		// sub command run with no command provided
		System.out.println("transact command");
	}

}
