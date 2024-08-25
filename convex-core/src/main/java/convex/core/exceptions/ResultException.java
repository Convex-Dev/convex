package convex.core.exceptions;

import convex.core.Result;

@SuppressWarnings("serial")
public class ResultException extends Exception {

	private Result result;

	public ResultException(Result r) {
		super("Error result ("+r.getErrorCode()+") : "+r.getValue());
		this.result=r;
	}
	
	public Result getResult() {
		return result;
	}

}
