package convex.core.exceptions;

import convex.core.Result;
import convex.core.data.Keyword;

/**
 * Exception class representing a Result failure
 * 
 * Useful in code where a successful Result is expected, but an error is received instead.
 */
@SuppressWarnings("serial")
public class ResultException extends Exception {

	private Result result;

	public ResultException(Result r) {
		super("Error result ("+r.getErrorCode()+") : "+r.getValue());
		this.result=r;
	}
	
	public ResultException(Keyword errorCode) {
		this(errorCode,"No more info");
	}

	public ResultException(Keyword errorCode, String message) {
		this(Result.error(errorCode, message));
	}

	public ResultException(Exception ex) {
		this(Result.fromException(ex));
	}

	public Result getResult() {
		return result;
	}

}
