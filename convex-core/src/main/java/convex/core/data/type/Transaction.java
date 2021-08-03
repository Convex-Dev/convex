package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.init.Init;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;

public class Transaction extends AStandardType<ATransaction>{

	protected Transaction() {
		super(ATransaction.class);
	}
	
	public static final Transaction INSTANCE = new Transaction();

	public static final ATransaction DEFAULT = Invoke.create(Init.GENESIS_ADDRESS, 0, (ACell)null);


	@Override
	public ATransaction defaultValue() {
		return DEFAULT;
	}

	@Override
	public String toString() {
		return "Transaction";
	}

}
