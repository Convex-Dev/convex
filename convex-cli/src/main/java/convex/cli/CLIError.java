package convex.cli;

/**
 * Exception class for general CLI errors to be reported to the user
 */
@SuppressWarnings("serial")
public class CLIError extends RuntimeException {

	protected int exitCode;

	public CLIError(String message) {
		this(message,null);
	}
	
	public CLIError(int exitCode,String message) {
		this(exitCode,message,null);
	}

	public CLIError(String message, Throwable cause) {
		this(ExitCodes.ERROR, message,cause);
	}
	
	public CLIError(int exitCode, String message, Throwable cause) {
		super(message, cause);
		this.exitCode=exitCode;
	}
	
	public int getExitCode() {
		return exitCode;
	}

}
