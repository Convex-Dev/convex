package convex.cli;

import java.io.Console;
import java.io.IOException;

import convex.cli.output.Coloured;
import convex.cli.output.RecordOutput;
import convex.core.Result;
import convex.core.util.Utils;
import picocli.CommandLine;

/**
 * Base class for Convex CLI command components and mixins
 */
public abstract class ACommand implements Runnable {

	/**
	 * Gets the current CLI main command instance
	 * @return CLI instance
	 */
	public abstract Main cli();
	
	public void showUsage() {
		CommandLine cl=new CommandLine(this);
		cl.setUsageHelpAutoWidth(true);
		cl.setUsageHelpWidth(100);
		cl.setUsageHelpLongOptionsMaxWidth(40);
		cl.usage(System.out);
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
	 * @param message
	 * @return
	 */
	public String prompt(String message) {
		if (!isInteractive()) throw new CLIError("Can't prompt for user input in non-interactive mode: "+message);
		
		if (isColoured()) message=Coloured.blue(message);
		inform(0,message);
		return System.console().readLine();
	}
	
	public char[] readPassword(String prompt) {
		Console c = System.console();
		if (c == null) {
			if (verbose()>=3) {
				informError("Can't give user prompt: "+prompt);
			}
			throw new CLIError(ExitCodes.USAGE,
					"Unable to request password because console is unavailable. Consider passing a password parameter, or running in interactive mode.");
		}
		
		if (isColoured()) prompt = Coloured.blue(prompt);
		return c.readPassword(prompt);
	}
	
	/**
	 * Prompt the user for a Y/N answer
	 * @param string
	 * @return
	 */
	public boolean question(String string) {
		if (!isInteractive()) throw new CLIError("Can't ask user question in non-interactive mode: "+string);
		try {
			if (isColoured()) string=Coloured.blue(string);
			inform(0,string);
			char c=(char)System.in.read(); // Doesn't work because console is not in non-blocking mode?
			if (c==-1) throw new CLIError("Unexpected end of input stream when expecting a keypress");
			if (Character.toLowerCase(c)=='y') return true;
		} catch (IOException e) {
			throw new CLIError("Unexpected error getting console input: "+e);
		}
		return false;
	}
	


	/**
	 * Checks if the CLI should output ANSI colours
	 * @return
	 */
	protected boolean isColoured() {
		return cli().isColoured();
	}
	

	public void informSuccess(String message) {
		if (isColoured()) message=Coloured.green(message);
		inform(1, message);
	}
	
	public void informError(String message) {
		if (isColoured()) message=Coloured.red(message);
		inform(1, message);
	}

	public void inform(String message) {
		if (isColoured()) message=Coloured.yellow(message);
		inform(1,message);
	}
	
	public void informWarning(String message) {
		if (isColoured()) message=Coloured.orange(message);
		inform(1,message);
	}

}
