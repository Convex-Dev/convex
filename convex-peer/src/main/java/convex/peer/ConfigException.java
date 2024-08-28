package convex.peer;

/**
 * Message thrown when a failure occurs during peer configuration
 */
@SuppressWarnings("serial")
public class ConfigException extends PeerException {

	public ConfigException(String message, Throwable cause) {
		super(message, cause);
		
	}
	
	public ConfigException(String message) {
		this(message, null);
		
	}
}
