package convex.core;

import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Keyword;

/**
 * Class for preparation of transaction results from the CVM
 * 
 * Mutable so that results can be accumulated efficiently while processing proceeds. Not intended for external usage.
 */
public class ResultContext {

	public ATransaction tx;
	public long juicePrice;
	public long memUsed=0;
	public Context context=null;
	public long totalFees=0;
	public long juiceUsed=0;
	public Keyword source=null;

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
		rc.context= Context.create(state).withError(error, message);
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

	public ACell getErrorCode() {
		return context.getErrorCode();
	}

	/**
	 * Get overall Juice fees, including the cost of the transaction
	 * @return Total juice fees
	 */
	public long getJuiceFees() {
		return Juice.mul(juiceUsed, juicePrice)+Juice.mul(Juice.priceTransaction(tx), juicePrice);
	}
	
	/**
	 * Get fees for execution juice only , excluding the cost of the transaction
	 * @return CVM Execution fees
	 */
	public long getExecutionFees() {
		return Juice.mul(juiceUsed, juicePrice);
	}

	public State getState() {
		return context.getState();
	}

	public long getMemoryFees() {
		return totalFees-getJuiceFees();
	}

	public boolean isError() {
		return context.isError();
	}

	public ResultContext withSource(Keyword sourceCode) {
		this.source=sourceCode;
		return this;
	}

	public Keyword getSource() {
		return source;
	}

}
