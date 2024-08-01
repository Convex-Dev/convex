package convex.cli;

import java.io.Console;
import java.util.Scanner;

import convex.cli.output.Coloured;
import convex.cli.output.RecordOutput;
import convex.core.Result;
import convex.core.util.Utils;
import picocli.CommandLine;

/**
 * Base class for Convex CLI commands
 */
public abstract class ACommand implements Runnable {

	/**
	 * Gets the current CLI main command instance
	 * @return CLI instance
	 */
	public abstract Main cli();
	
	public void showUsage() {
		CommandLine.usage(this, System.out);
	}
	
	public CommandLine commandLine() {
		return cli().commandLine;
	}

	/**
	 * Checks if the CLI is in strict (paranoid) mode
	 * @return
	 */
	public boolean isParanoid() {
		return cli().isParanoid();
	}
	
	public void paranoia(String message) {
		if (isParanoid())
			throw new CLIError("STRICT SECURITY: " + message);
	}

	
	protected void inform(int level, String message) {
		if (verbose()<level) return;
		commandLine().getErr().println(message);
	}

	/**
	 * Gets the current verbosity level for the CLI
	 * @return
	 */
	protected int verbose() {
		return cli().verbose;
	}
	
	public void println(String s) {
		if (s == null)
			s = "null";
		commandLine().getOut().println(s);
	}

	public void printResult(Result result) {
		commandLine().getOut().println(result.toString());
	}

	public void printRecord(RecordOutput output) {
		output.writeToStream(commandLine().getOut());
	}

	public void println(Object value) {
		println(Utils.toString(value));
	}

	/**
	 * Checks if the CLI is in interactive mode (user input permitted)
	 * @return
	 */
	public boolean isInteractive() {
		return cli().isInteractive();
	}
	
	/**
	 * Prompt the user for String input
	 * @param string
	 * @return
	 */
	public String prompt(String string) {
		if (!isInteractive()) throw new CLIError("Can't prompt for user input in non-interactive mode: "+string);
		
		inform(0,isColoured()?string:Coloured.blue(string));
		try (Scanner scanner = new Scanner(System.in)) {
			String s=scanner.nextLine();
			return s;
		}
	}
	
	public char[] readPassword(String prompt) {
		Console c = System.console();
		if (c == null) {
			throw new CLIError(
					"Unable to request password because console is unavaiable. Consider passing a password parameter, or running in interactive mode.");
		}
		
		if (isColoured()) prompt = Coloured.blue(prompt);
		return c.readPassword(prompt);
	}

	/**
	 * Checks if the CLI should output ANSI colours
	 * @return
	 */
	protected boolean isColoured() {
		return cli().isColoured();
	}


}
