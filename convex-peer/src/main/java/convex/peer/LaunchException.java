package convex.peer;

/**
 * Exception thrown when a failure occurs during peer launch
 */
@SuppressWarnings("serial")
public class LaunchException extends PeerException {

	public LaunchException(String message, Throwable cause) {
		super(message, cause);
		
	}
	
	public LaunchException(String message) {
		this(message, null);
		
	}
}
