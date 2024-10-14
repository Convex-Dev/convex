package convex.core.data.type;

import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.Address;

public class Transaction extends AStandardType<ATransaction>{

	protected Transaction() {
		super(ATransaction.class);
	}
	
	public static final Transaction INSTANCE = new Transaction();

	public static final ATransaction DEFAULT = Invoke.create(Address.create(0), 0, (ACell)null);


	@Override
	public ATransaction defaultValue() {
		return DEFAULT;
	}

	@Override
	public String toString() {
		return "Transaction";
	}

}
