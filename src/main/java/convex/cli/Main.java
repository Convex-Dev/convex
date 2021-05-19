package convex.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

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
	usageHelpAutoWidth=true,
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

	@Option(names={"-k", "--keystore"},
		defaultValue=Constants.KEYSTORE_FILENAME,
		scope = ScopeType.INHERIT,
		description="keystore filename. Default: ${DEFAULT-VALUE}")
	private String keyStoreFilename;

	@Option(names={"-p", "--password"},
		scope = ScopeType.INHERIT,
		description="Password to read/write to the Keystore")
	private String password;

	@Option(names={"-e", "--etch"},
		scope = ScopeType.INHERIT,
		description="Convex state storage filename. The default is to use a temporary storage filename in the /tmp folder.")
	private String etchStoreFilename;

	@Option(names={"--port"},
		scope = ScopeType.INHERIT,
		description="Port number to connect or create a peer.")
	private int port = 0;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		scope = ScopeType.INHERIT,
		description="Hostname to connect to a peer. Default: ${DEFAULT-VALUE}")
	private String hostname;


	public static void main(String[] args) {
		CommandLine commandLine = new CommandLine(new Main());
		commandLine.setUsageHelpLongOptionsMaxWidth(40);
		commandLine.setUsageHelpWidth(40 * 4);

		int retVal = commandLine.execute(args);
		System.exit(retVal);
	}

	public String getSessionFilename() {
		return Helpers.expandTilde(sessionFilename);
	}

	public String getPassword() {
		return password;
	}

	public String getKeyStoreFilename() {
		return Helpers.expandTilde(keyStoreFilename);
	}
	public int getPort() {
		return port;
	}
	public String getHostname() {
		return hostname;
	}
	public String getEtchStoreFilename() {
		return Helpers.expandTilde(etchStoreFilename);
	}

}
