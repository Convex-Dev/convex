package convex.cli;

import picocli.CommandLine;

/**
 * Exit codes
 * 
 * TODO: may need to make partially platform-specific?
 */
public class ExitCodes {
	/**
	 * Exit code for success (0)
	 */
	public static final int SUCCESS=CommandLine.ExitCode.OK;
	
	/**
	 * General catch-all error, probably IO
	 */
	public static final int ERROR=1;
	
	/**
	 * Usage error (2 according to standard bash / linux conventions)
	 */
	public static final int USAGE=CommandLine.ExitCode.USAGE;

	/**
	 * Fatal uncaught exception, should be reported as bug
	 */
	public static final int FATAL = 13;

	public static int getExitCode(Throwable t) {
		// TODO Possible to have more specific error
		if ((t instanceof Exception)||(t instanceof CLIError)) {
			return ERROR;
		}
		return FATAL;
	}
	
}
