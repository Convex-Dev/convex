package convex.peer;

@SuppressWarnings("serial")
public class ConfigException extends RuntimeException {

	public ConfigException(String message, Throwable cause) {
		super(message, cause);
		
	}
	
	public ConfigException(String message) {
		this(message, null);
		
	}
}
