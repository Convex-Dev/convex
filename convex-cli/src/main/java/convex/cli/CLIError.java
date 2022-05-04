package convex.cli;

/**
 * Exception class for general CLI errors to be reported to the user
 */
@SuppressWarnings("serial")
public class CLIError extends RuntimeException {

	public CLIError(String message) {
		super(message);
	}

	public CLIError(String message, Throwable cause) {
		super(message, cause);
	}

}
