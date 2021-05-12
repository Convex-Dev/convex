package convex.cli2;


import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Convex CLI implementation
 */
@Command(name="convex",
    subcommands = {
        Peer.class,
        Key.class,
        CommandLine.HelpCommand.class
    },
    mixinStandardHelpOptions=true,
    description="Convex Command Line Interface")

public class Main {

    @Option(names={ "-s", "--server"},
        defaultValue=Constants.SERVER_HOSTNAME,
        description="Specifies a peer server to use as current peer. Default: ${DEFAULT-VALUE}")
    String server;

	public static void main(String[] args) {
        int retVal = new CommandLine(new Main()).execute(args);
		System.exit(retVal);
	}

}
