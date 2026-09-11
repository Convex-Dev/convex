package convex.core.cvm;

import convex.core.cpos.Block;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Cells;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

public final class TransactionContext {
	public SignedData<ATransaction> signedTx; 
	public SignedData<Block> block;
	public Address origin;
	public State initialState;
	public long blockNumber;
	public long txNumber=0;
	
	
	public static TransactionContext createQuery(State initialState, Address origin) {
		TransactionContext ctx=create(initialState);
		ctx.origin=origin;
		return ctx;
	}

	public AccountKey getPeer() {
		if (block==null) return null;
		return block.getAccountKey();
	}

	public static TransactionContext create(State state) {
		TransactionContext ctx=new TransactionContext();
		ctx.initialState=state;
		ctx.blockNumber=state.getBlockNumber(); 
		return ctx;
	}

	public Address getOrigin() {
		return origin;
	}

	/**
	 * Returns a copy of this transaction context with a different origin, as used for a
	 * child transaction executed on behalf of a controlled account.
	 *
	 * @param newOrigin Origin address for the copy
	 * @return Transaction context with the given origin, or this instance if unchanged
	 */
	public TransactionContext withOrigin(Address newOrigin) {
		if (Cells.equals(origin, newOrigin)) return this;
		TransactionContext ctx=new TransactionContext();
		ctx.signedTx=signedTx;
		ctx.block=block;
		ctx.origin=newOrigin;
		ctx.initialState=initialState;
		ctx.blockNumber=blockNumber;
		ctx.txNumber=txNumber;
		return ctx;
	}

	public AVector<CVMLong> getLocation() {
		return Vectors.createLongs(blockNumber,txNumber);
	}
}
