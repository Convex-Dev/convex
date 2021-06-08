package convex.cli;

import java.io.File;
import java.io.IOException;
import java.security.KeyStore;
import java.util.Enumeration;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.data.Address;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.PropertiesDefaultProvider;
import picocli.CommandLine.ScopeType;

/**
* Convex CLI implementation
*/
@Command(name="convex",
	subcommands = {
		Account.class,
		Key.class,
		Local.class,
		Peer.class,
		Query.class,
		Status.class,
		Transaction.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=true,
	usageHelpAutoWidth=true,
	sortOptions = false,
	// headerHeading = "Usage:",
	// synopsisHeading = "%n",
	descriptionHeading = "%nDescription:%n%n",
	parameterListHeading = "%nParameters:%n",
	optionListHeading = "%nOptions:%n",
	commandListHeading = "%nCommands:%n",
	description="Convex Command Line Interface")

public class Main implements Runnable {

	private static final Logger log = Logger.getLogger(Main.class.getName());

	private static CommandLine commandLine;

	@Option(names={ "-c", "--config"},
		scope = ScopeType.INHERIT,
		description="Use the specified config file.%n All parameters to this app can be set by removing the leading '--', and adding"
			+ " a leading 'convex.'.%n So to set the keystore filename you can write 'convex.keystore=my_keystore_filename.dat'%n"
			+ "To set a sub command such as `./convex peer start index=4` index parameter you need to write 'convex.peer.start.index=4'")
	private String configFilename;

    @Option(names={"-e", "--etch"},
		scope = ScopeType.INHERIT,
		description="Convex state storage filename. The default is to use a temporary storage filename.")
	private String etchStoreFilename;

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

	@Option(names={"-s", "--session"},
	defaultValue=Constants.SESSION_FILENAME,
	description="Session filename. Defaults ${DEFAULT-VALUE}")
	private String sessionFilename;

    @Option(names={ "-v", "--verbose"},
		scope = ScopeType.INHERIT,
		description="Show more verbose log information.")
	private boolean verbose;


	@Override
	public void run() {
		// no command provided - so show help
		CommandLine.usage(new Main(), System.out);
	}

	public static void main(String[] args) {
		Main mainApp = new Main();
		int result = mainApp.execute(args);
		System.exit(result);
	}

	public int execute(String[] args) {
		commandLine = new CommandLine(this)
		.setUsageHelpLongOptionsMaxWidth(40)
		.setUsageHelpWidth(40 * 4);

		// do  a pre-parse to get the config filename. We need to load
		// in the defaults before running the full execute
		commandLine.parseArgs(args);
		loadConfig();
		if (verbose) {
			Logger root = Logger.getLogger("");
			Level targetLevel = Level.ALL;
			root.setLevel(targetLevel);
			for (Handler handler: root.getHandlers()) {
				handler.setLevel(targetLevel);
			}
			log.log(targetLevel, "Set level ALL");
		}
		int result = 0;
		try {
			result = commandLine.execute(args);
		} catch (Throwable t) {
			log.severe(t.getMessage());
			return 2;
		}
		return result;
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

	public String getEtchStoreFilename() {
		return Helpers.expandTilde(etchStoreFilename);
	}

	public AKeyPair loadKeyFromStore(String keystorePublicKey, int keystoreIndex) throws Error {

		AKeyPair keyPair = null;
		String publicKeyClean = keystorePublicKey.toLowerCase().replaceAll("^0x", "");

		if (password == null || password.isEmpty()) {
			throw new Error("You need to provide a keystore password");
		}

		if ( publicKeyClean.isEmpty() && keystoreIndex <= 0) {
			throw new Error("You need to provide a keystore public key identity via the --index or --public-key options");
		}

		File keyFile = new File(getKeyStoreFilename());
		try {
			if (!keyFile.exists()) {
				throw new Error("Cannot find keystore file "+keyFile.getCanonicalPath());
			}
			KeyStore keyStore = PFXTools.loadStore(keyFile, password);

			int counter = 1;
			Enumeration<String> aliases = keyStore.aliases();

			while (aliases.hasMoreElements()) {
				String alias = aliases.nextElement();
				if (counter == keystoreIndex || alias.indexOf(publicKeyClean) == 0) {
					keyPair = PFXTools.getKeyPair(keyStore, alias, password);
					break;
				}
				counter ++;
			}
		} catch (Throwable t) {
			throw new Error("Cannot load key store "+t);
		}

		if (keyPair==null) {
			throw new Error("Cannot find key in keystore");
		}
		return keyPair;
	}

	public Convex connectToSessionPeer(String hostname, int port, Address address, AKeyPair keyPair) throws Error {
		if (port == 0) {
			try {
				port = Helpers.getSessionPort(getSessionFilename());
			} catch (IOException e) {
				throw new Error("Cannot load the session control file");
			}
		}
		if (port == 0) {
			throw new Error("Cannot find a local port or you have not set a valid port number");
		}

		Convex convex = Helpers.connect(hostname, port, address, keyPair);
		if (convex==null) {
			throw new Error("Cannot connect to a peer");
		}
		return convex;
	}
}
