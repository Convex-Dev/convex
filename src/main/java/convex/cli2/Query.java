package convex.cli2;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 *
 * Convex Query sub command
 *
 */
@Command(name="query",
	mixinStandardHelpOptions=true,
    description="Execute a query on the current peer.")
public class Query implements Runnable {

    @ParentCommand
    private Main parent;


	public void run() {
        // sub command run with no command provided
        System.out.println("query command");
	}

}
