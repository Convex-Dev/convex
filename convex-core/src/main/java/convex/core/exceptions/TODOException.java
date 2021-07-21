package convex.core.exceptions;

@SuppressWarnings("serial")
public class TODOException extends RuntimeException {

	public TODOException(String message) {
		super("TODO: "+message);
	}
	
	public TODOException() {
		this("TODO");
	}

	public TODOException(Exception e) {
		super("TODO: "+e.getMessage(),e);
	}

}
