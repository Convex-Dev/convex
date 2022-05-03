package convex.cli;

/**
 * Exit codes
 * 
 * TODO: may need to make partially platform-specific?
 */
public class ExitCodes {
	/**
	 * Exit code for success
	 */
	public static final int SUCCESS=0;
	
	/**
	 * General catch-all error
	 */
	public static final int ERROR=1;

	/**
	 * Fatal exception
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
