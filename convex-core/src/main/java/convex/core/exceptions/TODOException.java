package convex.core.exceptions;

@SuppressWarnings("serial")
public class TODOException extends RuntimeException {

	public TODOException(String message) {
		super("TODO: "+message);
	}
	
	public TODOException() {
		super("TODO");
	}

	public TODOException(Exception e) {
		super("TODO: "+e.getMessage(),e);
	}

}
