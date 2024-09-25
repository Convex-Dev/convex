package convex.cli;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import convex.cli.account.Account;
import convex.cli.client.Query;
import convex.cli.client.Status;
import convex.cli.client.Transact;
import convex.cli.desktop.Desktop;
import convex.cli.etch.Etch;
import convex.cli.key.Key;
import convex.cli.local.Local;
import convex.cli.output.Coloured;
import convex.cli.peer.Peer;
import convex.core.util.FileUtils;
import convex.core.util.Utils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.ScopeType;

/**
 * Convex CLI implementation
 * 
 * This is the main `convex` command and root for child commands.
 */
@Command(name = "convex", 
		subcommands = { Account.class, Key.class, Local.class, Peer.class, Query.class, Status.class, Desktop.class,
		Etch.class, Transact.class, Help.class }, 
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
	private static Logger log = LoggerFactory.getLogger(Main.class);

	public CommandLine commandLine;
	
	public Main() {
		commandLine= new CommandLine(this);
		commandLine.setExecutionExceptionHandler(new Main.ExceptionHandler());
	}

	@Option(names = { "-S","--strict-security" }, 
			defaultValue = "false", 
			scope = ScopeType.INHERIT, 
			description = "Apply strict security. Will forbid actions with dubious security implications.")
	private boolean paranoid;

	@Option(names = { "-n","--noninteractive" }, 
			scope = ScopeType.INHERIT, 
			description = "Specify to disable interactive prompts. Useful for scripts.") 
	boolean nonInteractive;
	
	@Option(names = { "--no-color" }, 
			scope = ScopeType.INHERIT, 
			defaultValue = "${env:NO_COLOR}", 
			description = "Suppress ANSI colour output. Can also suppress with NO_COLOR environment variable.")
	private boolean noColour;

	@Option(names = { "-v","--verbose" }, 
			scope = ScopeType.INHERIT, 
			defaultValue = "${env:CONVEX_VERBOSE_LEVEL:-"+Constants.DEFAULT_VERBOSE_LEVEL+"}", 
			description = "Specify verbosity level. Use -v0 to suppress user output, -v5 for all log output. Default: ${DEFAULT-VALUE}") 
	private Integer verbose;

	@Override
	public void execute() {
		String art=Helpers.getConvexArt();
		if (isColoured()) art=Coloured.blue(art);
		inform(2,art);
		inform(2,Coloured.blue("Version: "+Utils.getVersion()));
		
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

			// do a pre-parse to get the config filename. We need to load
			// in the defaults before running the full execute
			try {
				commandLine.parseArgs(args);
			} catch (ParameterException t) {
				informError("ERROR: Unable to parse arguments: " + t.getMessage());
				informWarning("For more information on options and commands try 'convex help'.");
				return ExitCodes.ERROR;
			}

			if (commandLine.isUsageHelpRequested()) {
				showUsage();
				return ExitCodes.SUCCESS;
			} else if (commandLine.isVersionHelpRequested()) {
				commandLine.printVersionHelp(commandLine.getOut());
				return ExitCodes.SUCCESS;
			}

			setupVerbosity();

			int result = commandLine.execute(args);
			return result;
		} finally {
			commandLine.getOut().flush();
			commandLine.getErr().flush();

		}
	}

	private void setupVerbosity() {
		Level[] verboseLevels = { Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG, Level.TRACE, Level.TRACE };

		if (verbose == null)
			verbose = 0;
		if (verbose >= 0 && verbose < verboseLevels.length) {
			// Set root logger level?
//			try {
//			ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
//			root.setLevel(verboseLevels[verbose]);
//			} catch (XXException e) {
//				informWarning("Failed to set verbosity level: "+e.getMessage());
//			}
		} else {
			throw new CLIError(ExitCodes.USAGE,"Invalid verbosity level: " + verbose);
		}
	}

	/**
	 * Version provider class. 
	 * 
	 */
	// Note: GitHub Maven builds seem to need this to be public?
	public static final class VersionProvider implements IVersionProvider {
		@Override
		public String[] getVersion() throws Exception {
			String s = Utils.getVersion();
			return new String[] { s };
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
			} else if (ex.getClass().getSimpleName().equals("UserInterruptException")) {
				informError("Operation cancelled by user");
				if (verbose>=3) {
					ex.printStackTrace(err);
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

	@Override
	public boolean isParanoid() {
		return this.paranoid;
	}
	

	@Override
	public boolean isColoured() {
		return !noColour;
	}
	
	@Override
	public CommandLine commandLine() {
		return commandLine;
	}

	@Override
	public boolean isInteractive() {
		return !nonInteractive;
	}
	
	@Override
	protected int verbose() {
		if (verbose==null) verbose=Constants.DEFAULT_VERBOSE_LEVEL;
		return verbose;
	}

	/**
	 * Sets output to the specified file. 
	 * @param outFile String specifying file. `-` or `null` specifies STDOUT
	 */
	public void setOut(String outFile) {
		try {
			if (outFile == null || outFile.equals("-")) {
				log.debug("Setting output to STDOUT");
				commandLine.setOut(new PrintWriter(System.out));
			} else {
				File file = new File(outFile);
				file = FileUtils.ensureFilePath(file);
				log.debug("Setting output to "+file);
				PrintWriter pw = new PrintWriter(file);
				commandLine.setOut(pw);
			}
		} catch (IOException e) {
			throw new CLIError("Unavble to open output file: "+outFile);
		}
	}

	@Override
	public Main cli() {
		// We are the top level command!
		return this;
	}


}
