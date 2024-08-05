package convex.cli;

import picocli.CommandLine;

/**
 * Exit codes
 * 
 * See sysexits.h from FreeBSD for guides
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
	 * Usage error (64 as per )
	 */
	public static final int USAGE = 64;
	
	/**
	 * Error in input data, e.g. badly formatted
	 */
	public static final int DATAERR = 65;
	
	/**
	 * No input file / not readable
	 */
	public static final int NOINPUT = 66;
	
	/**
	 * No user exists (probably a bad account?)
	 */
	public static final int NOUSER = 67;

	/**
	 * No host exists (probably not a peer?)
	 */
	public static final int NOHOST = 68;


	/**
	 * Fatal uncaught software error, should be reported as bug
	 */
	public static final int SOFTWARE = 70;
	
	/**
	 * IO Error
	 */
	public static final int IOERR = 74;
	
	/**
	 * Temporary failure, e.g. a timeout
	 */
	public static final int TEMPFAIL = 75;
	
	
	/**
	 * Lack of permissions to complete an application operation
	 */
	public static final int NOPERM = 77;
	
	/**
	 * Something was not configured properly
	 */
	public static final int CONFIG = 78;


	public static int getExitCode(Throwable t) {
		if (t instanceof CLIError) {
			return ((CLIError)t).getExitCode();
		}
		
		// TODO Possible to have more specific errors?
		if (t instanceof Exception) {
			return ERROR;
		}
		
		// Was throwable but not an exception, so some kind of Error
		return SOFTWARE;
	}
	
}
