package convex.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
*
* Convex Transact sub command
*
*/
@Command(name="transact",
	mixinStandardHelpOptions=true,
	description="Execute a transaction on the network via the current peer.")
public class Transact implements Runnable {

	@ParentCommand
	private Main parent;


	public void run() {
		// sub command run with no command provided
		System.out.println("transact command");
	}

}
