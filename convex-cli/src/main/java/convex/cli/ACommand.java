package convex.cli;

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
}
