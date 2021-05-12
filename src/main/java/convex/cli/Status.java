package convex.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
*
* Convex Status sub command
*
*/
@Command(name="status",
	mixinStandardHelpOptions=true,
	description="Reports on the current status of the network.")
public class Status implements Runnable {

	@ParentCommand
	private Main parent;


	public void run() {
		// sub command run with no command provided
		System.out.println("status command");
	}

}
