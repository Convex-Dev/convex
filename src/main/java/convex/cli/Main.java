package convex.cli;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.logging.Logger;
import java.util.Properties;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.PropertiesDefaultProvider;
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

public class Main implements Runnable {

	private static final Logger log = Logger.getLogger(Main.class.getName());

	private static CommandLine commandLine;

	@Option(names={ "-c", "--config"},
		scope = ScopeType.INHERIT,
		description="Use the specified config file. All parameters to this app can be set by removing the leading '--'. ")
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
		//defaultValue="",
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

	@Override
	public void run() {
		// no command provided - so show help
		CommandLine.usage(new Main(), System.out);
	}

	public static void main(String[] args) {
		Main mainApp = new Main();
		commandLine = new CommandLine(mainApp)
			.setUsageHelpLongOptionsMaxWidth(40)
			.setUsageHelpWidth(40 * 4);

		// do  a pre-parse to get the config filename. We need to load
		// in the defaults before running the full execute
		commandLine.parseArgs(args);
		mainApp.loadConfig();
		int retVal = commandLine.execute(args);
		System.exit(retVal);
	}

	protected void loadConfig() {
		if (configFilename != null && !configFilename.isEmpty()) {
			String filename = Helpers.expandTilde(configFilename);
			File configFile = new File(filename);
			if (configFile.exists()) {
				PropertiesDefaultProvider defaultProvider = new PropertiesDefaultProvider(configFile);
				commandLine.setDefaultValueProvider(defaultProvider);
			}
		}
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
