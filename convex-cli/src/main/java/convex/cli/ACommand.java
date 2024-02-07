package convex.cli;

/**
 * Base class for Convex CLI commands
 */
public abstract class ACommand implements Runnable {

	/**
	 * Gets the current CLI main command instance
	 * @return
	 */
	public abstract Main cli();
}
