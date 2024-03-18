package convex.core;

import convex.core.transactions.ATransaction;

/**
 * Class for preparation of transaction results
 */
public class ResultContext {

	public ATransaction tx;
	public long juicePrice;

	public ResultContext(ATransaction transaction, long juicePrice) {
		this.juicePrice=juicePrice;
		this.tx=transaction;
	}

}
