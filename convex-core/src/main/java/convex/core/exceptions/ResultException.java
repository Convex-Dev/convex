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

	public ResultException(Result r, Exception cause) {
		super("Error result ("+r.getErrorCode()+") : "+r.getValue(),cause);
		this.result=r;
	}
	
	public ResultException(Result r) {
		this(r,null);
	}
	
	public ResultException(Keyword errorCode) {
		this(errorCode,"No more info");
	}

	public ResultException(Keyword errorCode, String message) {
		this(Result.error(errorCode, message));
	}

	/**
	 * Wraps an exception with its Result from {@link Result#fromException(Throwable)},
	 * which leaves the interrupt status alone: catch an {@link InterruptedException}
	 * separately and use {@link Result#fromInterrupt(InterruptedException)}.
	 *
	 * @param ex Exception caught
	 */
	public ResultException(Exception ex) {
		this(Result.fromException(ex),ex);
	}

	public Result getResult() {
		return result;
	}

}
