package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.data.RecordTest;
import convex.core.data.Vectors;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Symbols;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Call;
import convex.core.transactions.Transfer;

/**
 * Tests for Transactions, especially when applied in isolation to a State
 */
public class TransactionTest extends ACVMTest {

	protected TransactionTest() {
		super(InitTest.STATE);
	}
	
	Address HERO=InitTest.HERO;
	Address VILLAIN=InitTest.VILLAIN;
	long JP=Constants.INITIAL_JUICE_PRICE;
	
	protected State state() {
		return context().getState();
	}
	
	protected State apply(ATransaction t) {
		State s=state();
		Context<?> ctx= s.applyTransaction(t);
		assertFalse(ctx.isExceptional());
		return ctx.getState();
	}
	
	@Test 
	public void testTransfer() {
		Transfer t1=Transfer.create(HERO, 1, VILLAIN, 1000);
		State s=apply(t1);
		long expectedFees=Juice.TRANSFER*JP;
		assertEquals(1000+expectedFees,state().getAccount(HERO).getBalance()-s.getAccount(HERO).getBalance());
		assertEquals(expectedFees,s.getGlobalFees().longValue());
		
		doTransactionTests(t1);
	}
	
	@Test 
	public void testCall() {
		Call t1=Call.create(HERO, 1, HERO, Symbols.FOO, Vectors.empty());
		Context<?> ctx=state().applyTransaction(t1);
		assertEquals(ErrorCodes.STATE,ctx.getErrorCode());
		
		doTransactionTests(t1);
	}

	private void doTransactionTests(ATransaction t) {
		RecordTest.doRecordTests(t);
	}
	
	

}
