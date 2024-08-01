package convex.cli;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import convex.api.Convex;
import convex.cli.client.Query;
import convex.cli.client.Status;
import convex.cli.client.Transact;
import convex.cli.etch.Etch;
import convex.cli.key.Key;
import convex.cli.local.Local;
import convex.cli.output.Coloured;
import convex.cli.output.RecordOutput;
import convex.cli.peer.Peer;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.exceptions.TODOException;
import convex.core.util.Utils;
import etch.EtchStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.ScopeType;

/**
 * Convex CLI implementation
 */
@Command(name = "convex", 
		subcommands = { Account.class, Key.class, Local.class, Peer.class, Query.class, Status.class,
		Etch.class, Transact.class,
		CommandLine.HelpCommand.class }, 
		usageHelpAutoWidth = true, 
		sortOptions = true, 
		mixinStandardHelpOptions = true,
		// headerHeading = "Usage:",
		// synopsisHeading = "%n",
		parameterListHeading = "%nParameters:%n", 
		optionListHeading = "%nOptions:%n", 
		commandListHeading = "%nCommands:%n", 
		versionProvider = Main.VersionProvider.class, 
		description = "Convex Command Line Interface")

public class Main extends ACommand {
	static Logger log = LoggerFactory.getLogger(Main.class);

	public CommandLine commandLine = new CommandLine(this);

	@Mixin
	public StoreMixin storeMixin; 

	@Option(names = { "-k","--key" }, 
			defaultValue = "${env:CONVEX_KEY}", 
			scope = ScopeType.INHERIT, 
			description = "Public key to use. Default: ${DEFAULT-VALUE}")
	public String publicKey;

	@Option(names = { "-p","--keypass" }, 
			defaultValue = "${env:CONVEX_KEY_PASSWORD}", 
			scope = ScopeType.INHERIT, 
			description = "Key password in key store. Can also specify with CONVEX_KEY_PASSWORD environment variable.")
	private String keyPassword;

	@Option(names = { "-S","--strict-security" }, 
			defaultValue = "false", 
			scope = ScopeType.INHERIT, 
			description = "Apply strict security. Will forbid actions with dubious security implications.")
	private boolean paranoid;



	@Option(names = { "-n","--noninteractive" }, 
			scope = ScopeType.INHERIT, 
			description = "Specify to disable interactive prompts. Intended for scripts.") boolean nonInteractive;
	
	@Option(names = { "--no-color" }, 
			scope = ScopeType.INHERIT, 
			defaultValue = "${env:NO_COLOR}", 
			description = "Suppress ANSI colour output. Can also stop with NO_COLOR uenviornment variable")
	private boolean noColour;

	@Option(names = { "-v","--verbose" }, 
			scope = ScopeType.INHERIT, 
			defaultValue = "${env:CONVEX_VERBOSE_LEVEL:-2}", 
			description = "Specify verbosity level. Use -v0 to suppress user output, -v5 for all log output. Default: ${DEFAULT-VALUE}")
	private Integer verbose;

	public Main() {
		commandLine = commandLine.setExecutionExceptionHandler(new Main.ExceptionHandler());
	}

	@Override
	public void run() {
		// no command provided - so show help
		showUsage();
	}

	/**
	 * Java main(...) entry point when run as a Java application. Exits JVM process
	 * with specified exit code
	 * 
	 * @param args Command line arguments
	 */
	public static void main(String[] args) {
		Main mainApp = new Main();
		int result = mainApp.mainExecute(args);
		System.exit(result);
	}

	/**
	 * Command line execution entry point. Can be run from Java code without
	 * terminating the JVM.
	 * 
	 * @param args Command line arguments
	 * @return Process result value
	 */
	public int mainExecute(String[] args) {
		try {
			// commandLine
			// .setUsageHelpLongOptionsMaxWidth(80)
			// .setUsageHelpWidth(40 * 4);

			// do a pre-parse to get the config filename. We need to load
			// in the defaults before running the full execute
			try {
				commandLine.parseArgs(args);
			} catch (Exception t) {
				commandLine.getErr().println(Coloured.red("ERROR: Unable to parse arguments: " + t.getMessage()));
				commandLine.getErr().println("For more information on options and commands try 'convex help'.");
				return ExitCodes.ERROR;
			}

			if (commandLine.isUsageHelpRequested()) {
				commandLine.usage(commandLine.getOut());
				return ExitCodes.SUCCESS;
			} else if (commandLine.isVersionHelpRequested()) {
				commandLine.printVersionHelp(commandLine.getOut());
				return ExitCodes.SUCCESS;
			}

			Level[] verboseLevels = { Level.OFF, Level.WARN, Level.INFO, Level.DEBUG, Level.TRACE, Level.ALL };

			if (verbose == null)
				verbose = 0;
			if (verbose >= 0 && verbose < verboseLevels.length) {
				// Set root logger level?
				ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
				root.setLevel(verboseLevels[verbose]);
			} else {
				commandLine.getErr().println("ERROR: Invalid verbosity level: " + verbose);
				return ExitCodes.ERROR;
			}

			int result = commandLine.execute(args);
			return result;
		} finally {
			commandLine.getOut().flush();
			commandLine.getErr().flush();

		}
	}

	/**
	 * Version provider class
	 */
	public static final class VersionProvider implements IVersionProvider {
		@Override
		public String[] getVersion() throws Exception {
			String s = Main.class.getPackage().getImplementationVersion();
			return new String[] { "Convex version: " + s };
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
				CLIError ce = (CLIError) ex;
				String msg=ce.getMessage();
				informError(msg);
				Throwable cause = ce.getCause();
				if ((verbose>=3) && (cause != null)) {
					err.println("Underlying cause: ");
					cause.printStackTrace(err);
				}
			} else {
				if (verbose>=1) {
					ex.printStackTrace(err);
				}
			}
			// Exit with correct code for exception type
			return ExitCodes.getExitCode(ex);
		}

	}

	/**
	 * Keys the password for the current key
	 * 
	 * @return password
	 */
	public char[] getKeyPassword() {
		char[] keypass = null;

		if (this.keyPassword != null) {
			keypass = this.keyPassword.toCharArray();
		} else {
			if (!nonInteractive) {
				keypass = readPassword("Private Key Encryption Password: ");
			}

			if (keypass == null) {
				log.warn("No password for key: defaulting to blank password");
				keypass = new char[0];
			}
			
			this.keyPassword=new String(keypass);
		}
		if (keypass.length == 0) {
			paranoia("Cannot use an empty private key password");
		}
		return keypass;
	}


	/**
	 * Loads a keypair from configured keystore
	 * 
	 * @param publicKey String identifying the public key. May be a prefix
	 * @return Keypair instance, or null if not found
	 */
	public AKeyPair loadKeyFromStore(String publicKey) {
		if (publicKey == null)
			return null;

		AKeyPair keyPair = null;

		publicKey = publicKey.trim();
		publicKey = publicKey.toLowerCase().replaceFirst("^0x", "").strip();
		if (publicKey.isEmpty()) {
			return null;
		}

		char[] storePassword = storeMixin.getStorePassword(this);

		File keyFile = storeMixin.getKeyStoreFile();
		try {
			if (!keyFile.exists()) {
				throw new CLIError("Cannot find keystore file " + keyFile.getCanonicalPath());
			}
			KeyStore keyStore = PFXTools.loadStore(keyFile, storePassword);

			Enumeration<String> aliases = keyStore.aliases();

			while (aliases.hasMoreElements()) {
				String alias = aliases.nextElement();
				if (alias.indexOf(publicKey) == 0) {
					log.trace("found keypair " + alias);
					keyPair = PFXTools.getKeyPair(keyStore, alias, getKeyPassword());
					break;
				}
			}
		} catch (Exception t) {
			throw new CLIError("Cannot load key store", t);
		}

		return keyPair;
	}

	/**
	 * Generate key pairs and add to store. Does not save store!
	 * 
	 * @param count Number of key pairs to generate
	 * @return List of key pairs
	 */
	public List<AKeyPair> generateKeyPairs(int count, char[] keyPassword) {
		List<AKeyPair> keyPairList = new ArrayList<>(count);

		// generate `count` keys
		for (int index = 0; index < count; index++) {
			AKeyPair keyPair = AKeyPair.generate();
			keyPairList.add(keyPair);
			storeMixin.addKeyPairToStore(this, keyPair, keyPassword);
		}

		return keyPairList;
	}

	/**
	 * Connect as a client to the currently configured Convex network
	 * 
	 * @return Convex instance
	 */
	public Convex connect() {
		throw new TODOException();
	}

	public void saveKeyStore() {
		if (storeMixin.keystorePassword==null) throw new CLIError("Key store password not provided");
		storeMixin.saveKeyStore(storeMixin.keystorePassword.toCharArray());
	}

	public boolean isParanoid() {
		return this.paranoid;
	}

	public void println(String s) {
		if (s == null)
			s = "null";
		commandLine.getOut().println(s);
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

	public boolean isInteractive() {
		return !nonInteractive;
	}

	public char[] readPassword(String prompt) {
		Console c = System.console();
		if (c == null) {
			throw new CLIError(
					"Unable to request password because console is unavaiable. Consider passing a password parameter, or running in interactive mode.");
		}
		
		if (!noColour) prompt = Coloured.blue(prompt);
		return c.readPassword(prompt);
	}

	public void informSuccess(String message) {
		inform(1, noColour?message:Coloured.green(message));
	}
	
	public void informError(String message) {
		inform(1, noColour?message:Coloured.red(message));
	}

	public void inform(String message) {
		inform(1, noColour?message:Coloured.yellow(message));
	}
	
	private void inform(int level, String message) {
		if (verbose<level) return;
		commandLine.getErr().println(message);
	}

	public void paranoia(String message) {
		if (isParanoid())
			throw new CLIError("STRICT SECURITY: " + message);
	}

	public void setOut(String outFile) {
		if (outFile == null || outFile.equals("-")) {
			commandLine.setOut(new PrintWriter(System.out));
		} else {
			File file = new File(outFile);
			try {
				file = Utils.ensurePath(file);
				PrintWriter pw = new PrintWriter(file);
				commandLine.setOut(pw);
			} catch (IOException e) {
				Utils.sneakyThrow(e);
			}
		}
	}

	public EtchStore getEtchStore(String etchStoreFilename) {
		if (etchStoreFilename == null) {
			throw new CLIError(
					"No Etch store file specified. Maybe include --etch option or set environment variable CONVEX_ETCH_FILE ?");
		}

		File etchFile = Utils.getPath(etchStoreFilename);

		try {
			EtchStore store = EtchStore.create(etchFile);
			return store;
		} catch (IOException e) {
			throw new CLIError("Unable to load Etch store at: " + etchFile + " cause: " + e.getMessage());
		}
	}

	/**
	 * Prompt the user for a Y/N answer
	 * @param string
	 * @return
	 */
	public boolean question(String string) {
		if (!isInteractive()) throw new CLIError("Can't ask user question in non-interactive mode: "+string);
		try {
			inform(0,noColour?string:Coloured.blue(string));
			char c=(char)System.in.read(); // Doesn't work because console is not in non-blocking mode?
			if (c==-1) throw new CLIError("Unexpected end of input stream when expecting a keypress");
			if (Character.toLowerCase(c)=='y') return true;
		} catch (IOException e) {
			throw new CLIError("Unexpected error getting console input: "+e);
		}
		return false;
	}
	
	/**
	 * Prompt the user for String input
	 * @param string
	 * @return
	 */
	public String prompt(String string) {
		if (!isInteractive()) throw new CLIError("Can't prompt for user input in non-interactive mode: "+string);
		
		inform(0,noColour?string:Coloured.blue(string));
		try (Scanner scanner = new Scanner(System.in)) {
			String s=scanner.nextLine();
			return s;
		}
	}

	@Override
	public Main cli() {
		// We are the top level command!
		return this;
	}


}
