package convex.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
* Convex CLI implementation
*/
@Command(name="convex",
	subcommands = {
		Peer.class,
		Key.class,
		Query.class,
		Transact.class,
		Status.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=true,
	description="Convex Command Line Interface")

public class Main {

	@Option(names={ "-c", "--config"},
		defaultValue=Constants.CONFIG_FILENAME,
		description="Use the specified config file. Default: ${DEFAULT-VALUE}")
	private String configFilename;

	@Option(names={"-s", "--session"},
	defaultValue=Constants.SESSION_FILENAME,
	description="Session filename. Defaults ${DEFAULT-VALUE}")

	private String sessionFilename;

	public static void main(String[] args) {
		int retVal = new CommandLine(new Main()).execute(args);
		System.exit(retVal);
	}

	public String getSessionFilename() {
		return Helpers.expandTilde(sessionFilename);
	}

}
