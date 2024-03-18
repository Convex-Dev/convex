package convex.core;

import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.lang.Context;
import convex.core.transactions.ATransaction;

/**
 * Class for preparation of transaction results
 */
public class ResultContext {

	public ATransaction tx;
	public long juicePrice;
	public long memUsed=0;
	public Context context=null;

	public ResultContext(ATransaction transaction, long juicePrice) {
		this.juicePrice=juicePrice;
		this.tx=transaction;
	}

	public ResultContext withContext(Context ctx) {
		this.context=ctx;
		return this;
	}

	public static ResultContext error(State state, Keyword error, String message) {
		ResultContext rc=new ResultContext(null,0);
		rc.context= Context.createFake(state).withError(error, message);
		return rc;
	}

	public static ResultContext error(State state, Keyword error, AString message) {
		return error(state,error,message.toString());
	}

}
