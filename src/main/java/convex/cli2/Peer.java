package convex.cli2;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 *
 * Convex peer sub commands
 *
 */
@Command(name="peer",
	mixinStandardHelpOptions=true,
    description="Operates a local peer.")
public class Peer implements Runnable {

    @ParentCommand
    private Main parent;

    @Option(names={"-p", "--port"},
        description="Specify a port to run the local peer.")
    private int port;

    // peer start command
    @Command(name="start",
        mixinStandardHelpOptions=true,
        description="Starts a peer server.")
    void start() {
        System.out.printf("peer start command at port: %d, server: %s\n", port, parent.server);
    }

	public void run() {
        // sub command run with no command provided
        CommandLine.usage(new Peer(), System.out);
	}

}
