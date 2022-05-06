package convex.cli;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import convex.api.Convex;
import convex.cli.output.RecordOutput;
import convex.cli.peer.SessionItem;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.init.Init;
import convex.core.util.Utils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParseResult;
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
	usageHelpAutoWidth=true,
	sortOptions = false,
	mixinStandardHelpOptions=true,
	// headerHeading = "Usage:",
	// synopsisHeading = "%n",
	descriptionHeading = "%nDescription:%n%n",
	parameterListHeading = "%nParameters:%n",
	optionListHeading = "%nOptions:%n",
	commandListHeading = "%nCommands:%n",
	versionProvider = Main.VersionProvider.class,
	description="Convex Command Line Interface")

public class Main implements Runnable {




	private static Logger log = LoggerFactory.getLogger(Main.class);


	CommandLine commandLine=new CommandLine(this);

	@Option(names={ "-c", "--config"},
		scope = ScopeType.INHERIT,
		description="Use the specified config file. If not specified, will check ~/.convex/convex.config"
		    + "%n All parameters to this app can be set by removing the leading '--', and adding"
			+ " a leading 'convex.'.%n So to set the keystore filename you can write 'convex.keystore=my_keystore_filename.dat'%n"
			+ "To set a sub command such as `./convex peer start index=4` index parameter you need to write 'convex.peer.start.index=4'")
	private String configFilename;

    @Option(names={"-e", "--etch"},
		scope = ScopeType.INHERIT,
		description="Convex Etch database filename. A temporary storage file will be created if required.")
	private String etchStoreFilename;

	@Option(names={"-k", "--keystore"},
		defaultValue=Constants.KEYSTORE_FILENAME,
		scope = ScopeType.INHERIT,
		description="Keystore filename. Default: ${DEFAULT-VALUE}")
	private String keyStoreFilename;

	@Option(names={"-p", "--password"},
		scope = ScopeType.INHERIT,
		//defaultValue="",
		description="Password to read/write to the Keystore")
	private String password;
	
	@Option(names={"-pi", "--password-interactive"},
			scope = ScopeType.INHERIT,
			//defaultValue="",
			description="Specify to request an interactive password prompt if not otherwise specified")
	private boolean passwordInteractive;

	@Option(names={"-s", "--session"},
	defaultValue=Constants.SESSION_FILENAME,
    scope = ScopeType.INHERIT,
	description="Session filename. Default: ${DEFAULT-VALUE}")
	private String sessionFilename;

    @Option(names={ "-v", "--verbose"},
		scope = ScopeType.INHERIT,
		description="Show more verbose log information. You can increase verbosity by using multiple -v or -vvv")
	private boolean[] verbose = new boolean[0];

	public Main() {
		commandLine=commandLine.setExecutionExceptionHandler(new Main.ExceptionHandler());
	}

	@Override
	public void run() {
		// no command provided - so show help
		CommandLine.usage(new Main(), System.out);
	}

	/**
	 * Java main(...) entry point when run as a Java application.
	 * Exits JVM process with specified exit code
	 */
	public static void main(String[] args) {
		Main mainApp = new Main();
		int result = mainApp.mainExecute(args);
		System.exit(result);
	}

	/**
	 * Command line execution entry point. Can be run from Java code without 
	 * terminating the JVM.
	 * @param args Command line arguments
	 * @return Process result value
	 */
	public int mainExecute(String[] args) {
		commandLine
		.setUsageHelpLongOptionsMaxWidth(40)
		.setUsageHelpWidth(40 * 4);

		// do  a pre-parse to get the config filename. We need to load
		// in the defaults before running the full execute
		try {
			commandLine.parseArgs(args);
			loadConfig();
		} catch (Throwable t) {
			System.err.println("unable to parse arguments " + t);
		}

		ch.qos.logback.classic.Logger parentLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

		Level[] verboseLevels = {Level.WARN, Level.INFO, Level.DEBUG, Level.TRACE, Level.ALL};

		parentLogger.setLevel(Level.WARN);
		if (verbose.length > 0 && verbose.length <= verboseLevels.length) {
			parentLogger.setLevel(verboseLevels[verbose.length]);
			log.info("set level to {}", parentLogger.getLevel());
		}

		int result = 0;
		result = commandLine.execute(args);
		return result;
	}
	
	/**
	 * Version provider class
	 */
	public static final class VersionProvider implements IVersionProvider {
		@Override
		public String[] getVersion() throws Exception {
			String s=Main.class.getPackage().getImplementationVersion();
			return new String[] {s};
		}

	}
	
	/**
	 * Exception handler class
	 */
	private class ExceptionHandler implements IExecutionExceptionHandler {

		@Override
		public int handleExecutionException(Exception ex, CommandLine commandLine, ParseResult parseResult)
				throws Exception {
			PrintWriter err = commandLine.getErr();
			if (ex instanceof CLIError) {
				CLIError ce=(CLIError)ex;
				err.println(ce.getMessage());
				Throwable cause=ce.getCause();
				if (cause!=null) {
					err.println("Underlying cause: ");
					cause.printStackTrace(err);
				}
			} else {
				ex.printStackTrace(err);
			}
			return ExitCodes.getExitCode(ex);
		}

	}

	/**
	 * Loads the specified config file.
	 * @return true if config file correctly loaded, false otherwise (e.g. if it does not exist)
	 */
	protected boolean loadConfig() {
		String filename=null;
		if (configFilename != null && !configFilename.isEmpty()) {
			filename = Helpers.expandTilde(configFilename);
		}
		
		if (filename!=null) {
			File configFile = new File(filename);
			if (configFile.exists()) {
				PropertiesDefaultProvider defaultProvider = new PropertiesDefaultProvider(configFile);
				commandLine.setDefaultValueProvider(defaultProvider);
				return true;
			} else {
				log.warn("Config file does not exist: "+configFilename);
				return false;
			}
		}
		return false;
	}

	public String getSessionFilename() {
        if (sessionFilename != null) {
			return Helpers.expandTilde(sessionFilename.strip());
		}
		return null;
	}

	boolean passwordAcquired=false;
	
	/**
	 * Get the currently configured password for the keystore. Will emit warning and default to
	 * blank password if not provided
	 * @return Password string
	 */
	public String getPassword() {
		if (!passwordAcquired) {
			if (passwordInteractive) {
				Console console = System.console(); 
				password= console.readLine("Password: ");
			} else {
				if (password==null) {
					log.warn("No password for keystore: defaulting to blank password");
					password="";
				}
			}
			passwordAcquired=true;
		}
		return password;
	}
	
	/**
	 * Sets the currently defined keystore password
	 * @param password Password to use
	 */
	public void setPassword(String password) {
		this.password=password;
	}

	/**
	 * Gets the keystore file name currently used for the CLI
	 * @return File name, or null if not specified
	 */
	public String getKeyStoreFilename() {
		if ( keyStoreFilename != null) {
			return Helpers.expandTilde(keyStoreFilename).strip();
		}
		return null;
	}

	public String getEtchStoreFilename() {
		if ( etchStoreFilename != null) {
			return Helpers.expandTilde(etchStoreFilename).strip();
		}
		return null;
	}

	private boolean keyStoreLoaded=false;
	private KeyStore keyStore=null;
	
	/**
	 * Gets the current key store
	 * @return KeyStore instance, or null if it does not exist
	 */
	public KeyStore getKeystore() {
		if (keyStoreLoaded==false) {
			keyStore=loadKeyStore(false);
			keyStoreLoaded=true;
		}
		return keyStore;
	}
	
	/**
	 * Gets the current key store. 
	 * @param create Flag to indicate if keystore should be created
	 * @return KeyStore instance
	 */
	public KeyStore getKeystore(boolean create) {
		if (keyStoreLoaded==false) {
			keyStore=loadKeyStore(create);
			if (keyStore==null) throw new CLIError("Keystore does not exist!");
			keyStoreLoaded=true;
		}
		return keyStore;
	}
	
	/**
	 * Loads the currently configured get Store
	 * @param isCreate Flag to indicate if keystore should be created if absent
	 * @return KeyStore instance, or null if does not exist
	 */
	KeyStore loadKeyStore(boolean isCreate)  {
		String password=getPassword();
		File keyFile = new File(getKeyStoreFilename());
		try {
			if (keyFile.exists()) {
				keyStore = PFXTools.loadStore(keyFile, password);
			} else if (isCreate) {
				log.warn("No keystore exists, creating at: "+keyFile.getCanonicalPath());
				Helpers.createPath(keyFile);
				keyStore = PFXTools.createStore(keyFile, password);
			}
		} catch (Exception t) {
			throw new CLIError("Unable to read keystore at: "+keyFile,t);
		}
		keyStoreLoaded=true;
		return keyStore;
	}

	public AKeyPair loadKeyFromStore(String publicKey) {

		AKeyPair keyPair = null;

		String publicKeyClean = "";
		if (publicKey != null) {
			publicKeyClean = publicKey.toLowerCase().replaceAll("^0x", "").strip();
		}


		String searchText = publicKeyClean;
		String password=getPassword();

		File keyFile = new File(getKeyStoreFilename());
		try {
			if (!keyFile.exists()) {
				throw new Error("Cannot find keystore file "+keyFile.getCanonicalPath());
			}
			KeyStore keyStore = PFXTools.loadStore(keyFile, password);

			Enumeration<String> aliases = keyStore.aliases();

			while (aliases.hasMoreElements()) {
				String alias = aliases.nextElement();
				if ((alias.indexOf(publicKeyClean) == 0 && !publicKeyClean.isEmpty())) {
					log.trace("found keypair " + alias);
					keyPair = PFXTools.getKeyPair(keyStore, alias, password);
					break;
				}
			}
		} catch (Throwable t) {
			throw new Error("Cannot load key store "+t);
		}

		if (keyPair==null) {
			throw new Error("Cannot find key in keystore '" + searchText + "'");
		}
		return keyPair;
	}

	public Convex connectToSessionPeer(String hostname, int port, Address address, AKeyPair keyPair) {
		SessionItem item;
		Convex convex = null;
		try {
			if (port == 0) {
				item = Helpers.getSessionItem(getSessionFilename());
				if (item != null) {
					port = item.getPort();
				}
			}
			if (port == 0) {
				throw new Error("Cannot find a local port or you have not set a valid port number");
			}
			InetSocketAddress host=new InetSocketAddress(hostname.strip(), port);
			convex = Convex.connect(host, address, keyPair);
		} catch (Throwable t) {
			throw new Error("Cannot connect to a local peer " + t);
		}
		return convex;
	}

	public Convex connectAsPeer(int peerIndex) throws Error {
		Convex convex = null;
		try {
			SessionItem item = Helpers.getSessionItem(getSessionFilename(), peerIndex);
			AccountKey peerKey = item.getAccountKey();
			log.debug("peer public key {}", peerKey.toHexString());
			AKeyPair keyPair = loadKeyFromStore(peerKey.toHexString());
			log.debug("peer key pair {}", keyPair.getAccountKey().toHexString());
			Address address = Init.getGenesisPeerAddress(peerIndex);
			log.debug("peer address {}", address);
			InetSocketAddress host = item.getHostAddress();
			log.debug("connect to peer {}", host);
			convex = Convex.connect(host, address, keyPair);
		} catch (Throwable t) {
			throw new Error("Cannot connect as a peer " + t);
		}
		return convex;
	}

	// Generate key pairs and add to store
	public List<AKeyPair> generateKeyPairs(int count) {
		List<AKeyPair> keyPairList = new ArrayList<>(count);

		// generate `count` keys
		for (int index = 0; index < count; index ++) {
			AKeyPair keyPair = AKeyPair.generate();
			keyPairList.add(keyPair);
			addKeyPairToStore(keyPair);
		}

		return keyPairList;
	}

	/**
	 * Adds key pair to store. Does not save keystore!
	 * @param keyPair Keypair to add
	 */
	public void addKeyPairToStore(AKeyPair keyPair) {
		// get the password of the key store file (may default to blank)
		String password = getPassword();

		KeyStore keyStore = getKeystore();
		if (keyStore==null) {
			throw new CLIError("Trying to add key pair but keystore does not exist");
		}
		try {
			// save the key in the keystore
			PFXTools.setKeyPair(keyStore, keyPair, password);
		} catch (Throwable t) {
			throw new CLIError("Cannot store the key to the key store "+t);
		}

	}
	
	void saveKeyStore() {
		// save the keystore file
		if (keyStore==null) throw new CLIError("Trying to save a keystore that has not been loaded!");
		try {
			PFXTools.saveStore(keyStore, new File(getKeyStoreFilename()), password);
		} catch (Throwable t) {
			throw Utils.sneakyThrow(t);
		}
	}

	int[] getPortList(String ports[], int count) throws NumberFormatException {
		Pattern rangePattern = Pattern.compile(("([0-9]+)\\s*-\\s*([0-9]*)"));
		List<String> portTextList = Helpers.splitArrayParameter(ports);
		List<Integer> portList = new ArrayList<Integer>();
		int countLeft = count;
		for (int index = 0; index < portTextList.size() && countLeft > 0; index ++) {
			String item = portTextList.get(index);
			Matcher matcher = rangePattern.matcher(item);
			if (matcher.matches()) {
				int portFrom = Integer.parseInt(matcher.group(1));
				int portTo = portFrom  + count + 1;
				if (!matcher.group(2).isEmpty()) {
					portTo = Integer.parseInt(matcher.group(2));
				}
				for ( int portIndex = portFrom; portIndex <= portTo && countLeft > 0; portIndex ++, --countLeft ) {
					portList.add(portIndex);
				}
			}
			else if (item.strip().length() == 0) {
			}
			else {
				portList.add(Integer.parseInt(item));
				countLeft --;
			}
		}
		return portList.stream().mapToInt(Integer::intValue).toArray();
	}

	public void println(String s) {
		if (s==null) s="null";
		commandLine.getOut().println(s);
	}

	public void printError(Result result) {
		commandLine.getErr().println(result.toString());
	}
	
	public void printResult(Result result) {
		commandLine.getOut().println(result.toString());
	}

	public void printRecord(RecordOutput output) {
		output.writeToStream(commandLine.getOut());
	}

	public void println(Object value) {
		println(Utils.toString(value));
	}


}
