package convex.core;

import convex.core.data.ACell;
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
	public long totalFees=0;
	public long juiceUsed=0;

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

	public ACell getResult() {
		return context.getResult();
	}

	public static ResultContext fromContext(Context ctx) {
		State state=ctx.getState();
		ResultContext rc=new ResultContext(null, state.getJuicePrice().longValue());
		rc.context=ctx;
		rc.juiceUsed=ctx.getJuiceUsed();
		return rc;
	}

	public Object getErrorCode() {
		return context.getErrorCode();
	}

}
