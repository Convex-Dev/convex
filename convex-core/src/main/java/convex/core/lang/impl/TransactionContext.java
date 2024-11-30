package convex.core.lang.impl;

import convex.core.cpos.Block;
import convex.core.cvm.Address;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;

public final class TransactionContext {
	public SignedData<ATransaction> tx; 
	public SignedData<Block> block;
	public Address origin;
	public State initialState;
	
	
	public static TransactionContext createQuery(State initialState, Address origin) {
		TransactionContext ctx=new TransactionContext();
		ctx.origin=origin;
		ctx.initialState=initialState;
		return ctx;
	}

	public AccountKey getPeer() {
		if (block==null) return null;
		return block.getAccountKey();
	}

	public static TransactionContext create(State state) {
		TransactionContext ctx=new TransactionContext();
		ctx.initialState=state;
		return ctx;
	}
}
