package convex.core.exceptions;

/**
 * Class representing an unexpected error that should halt the system
 * 
 * Should not usually be caught
 */
@SuppressWarnings("serial")
public class Panic extends Error {
	public Panic(String message, Throwable cause) {
		super(message,cause);
	}
	
	public Panic(String message) {
		this(message,null);
	}
	
	public Panic(Throwable cause) {
		this(null,cause);
	}
}
