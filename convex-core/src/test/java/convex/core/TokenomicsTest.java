package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Reader;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.transactions.Transfer;

import static convex.test.Assertions.*;

/**
 * Tests for tokenomics operations
 */
public class TokenomicsTest extends ACVMTest {
	// starting balances etc.
	long BALANCE=context().getBalance(HERO);
	long ALLOWANCE=context().getAccountStatus(HERO).getMemory();
	double MEMPRICE=context().getState().getMemoryPrice();
	double TOTAL_MEMORY=context().getState().computeTotalMemory();
	long SEQ=context().getAccountStatus(HERO).getSequence()+1;

	@Test public void testJuiceOnly() {
		// A transaction that burns some juice, but not much else
		Invoke t=Invoke.create(HERO, SEQ, Reader.read("(+ 2 3)"));
		ResultContext rc=runTransaction(t);
		
		// we should have used some juice, but no memory
		assertEquals(0,rc.memUsed); 
		assertTrue(rc.juiceUsed>0);
		assertTrue(rc.juicePrice>0);
		
		// total fees should be for execution only, as we didn't make any memory changes
		long txJuice=Juice.priceTransaction(t);
		assertEquals(rc.totalFees,rc.juicePrice*(rc.juiceUsed+txJuice));
		
		// HERO should pay juice fees for transaction, nothing else
		assertEquals(rc.getJuiceFees(),BALANCE-rc.context.getBalance(HERO));
		
		checkFinalState(rc,false);
	}
	
	@Test
	public void testTransfer() {
		long AMT=1000;
		Transfer t=Transfer.create(HERO, BALANCE, VILLAIN, AMT);
		ResultContext rc=runTransaction(t);
		
		assertFalse(rc.isError());
		
		// HERO should pay juice fees plus amount of transaction
		assertEquals(rc.getJuiceFees()+AMT,BALANCE-rc.context.getBalance(HERO));
		
		checkFinalState(rc,false);
	}
	
	@Test
	public void testTransferFail() {
		Transfer t=Transfer.create(HERO, BALANCE, VILLAIN, Coin.SUPPLY);
		ResultContext rc=runTransaction(t);
		
		// We failed because of insufficient funds for transfer
		assertEquals(ErrorCodes.FUNDS,rc.getErrorCode());
		
		// HERO should pay juice fees only (transfer never happened)
		assertEquals(rc.getJuiceFees(),BALANCE-rc.context.getBalance(HERO));
		
		checkFinalState(rc,false);
	}
	
	@Test public void testSmallMemorySell() {
		// sell one byte of allowance
		Invoke t=Invoke.create(HERO, SEQ, Reader.read("(do (set-memory (dec *memory*)))"));
		ResultContext rc=runTransaction(t);

		// we should have slightly decreased pool memory price
		assertLess(rc.getState().getMemoryPrice(),MEMPRICE);
		
		assertEquals(0,rc.memUsed); 
		assertEquals(ALLOWANCE-1,rc.context.getAccountStatus(HERO).getMemory()); 
		
		checkFinalState(rc,false);
	}
	
	@Test public void testSmallMemoryBuy() {
		// sell one byte of allowance
		Invoke t=Invoke.create(HERO, SEQ, Reader.read("(do (set-memory (inc *memory*)))"));
		ResultContext rc=runTransaction(t);
	
		// we should have slightly increased pool memory price
		assertGreater(rc.getState().getMemoryPrice(),MEMPRICE);

		assertEquals(0,rc.memUsed); 
		assertEquals(ALLOWANCE+1,rc.context.getAccountStatus(HERO).getMemory()); 
		
		checkFinalState(rc,false);

	}
	
	@Test public void testMemoryUsed() {
		// sell all memory, then make an allocation of 16 bytes
		Invoke t=Invoke.create(HERO, SEQ, Reader.read("(do (set-memory 0) (def a 0x12345678123456781234567812345678))"));
		ResultContext rc=runTransaction(t);
		
		// memory of more than 16 bytes should be used, but not more than 100
		assertGreater(rc.memUsed,16);
		assertLess(rc.memUsed,100);
		
		assertEquals(rc.totalFees,rc.getJuiceFees()+rc.getMemoryFees());
		
		checkFinalState(rc,true);

		
	}
	
	@Test public void testMemoryFail() {
		// This transaction fails by transferring most coins and all allowance away, then allocating more data
		long COINS_LEFT=100000;
		Invoke t=Invoke.create(HERO, SEQ, Reader.read("(do (set-memory 0) (transfer "+VILLAIN+" (- *balance* "+COINS_LEFT+")) (def a 0x12345678123456781234567812345678))"));
		ResultContext rc=runTransaction(t);

		// juice fees shouldn't blow up transaction alone, but memory cost does
		assertLess(rc.getJuiceFees(),COINS_LEFT); 

		// We failed because of memory allocation
		assertEquals(ErrorCodes.MEMORY,rc.getErrorCode());
		
		// shouldn't move memory price, since transaction was rolled back
		assertEquals(MEMPRICE,rc.getState().getMemoryPrice()); 
		
		checkFinalState(rc,false);

	}
	
	@Test public void testMemoryOverbuy() {
		long initialBal=context().getBalance(HERO);
		long COINS_LEFT=100000;
		
		// A transaction that sells all memory, then transfers away most coins, then tries to buy memory back
		Invoke t=Invoke.create(HERO, SEQ, Reader.read("(do (set-memory 0) (transfer "+VILLAIN+" (- *balance* "+COINS_LEFT+")) (set-memory 10000))"));
		ResultContext rc=runTransaction(t);
		
		// juice fees shouldn't blow up transaction alone, but memory cost does
		assertLess(rc.getJuiceFees(),COINS_LEFT); 
		
		// We failed due to insufficient coin funds for memory purchase
		assertEquals(ErrorCodes.FUNDS,rc.getErrorCode());
		
		// should have paid full juice fees
		assertEquals(rc.getJuiceFees(),initialBal-rc.context.getBalance(HERO));

		checkFinalState(rc,false);

	}
	
	@Test public void testJuiceExceeded() {
		// Transfer away almost all coins, so we definitely don't have enough to complete transaction
		Invoke t=Invoke.create(HERO, SEQ, Reader.read("(do (transfer "+VILLAIN+" (- *balance* 5)))"));
		ResultContext rc=runTransaction(t);
		
		// Transaction should fail due to juice fees
		assertEquals(ErrorCodes.JUICE,rc.getErrorCode());
		
		// transaction is rolled back so our transfer is refunded, but juice fees still applied which affects account balance
		long newBalance=rc.context.getBalance(HERO);
		assertGreater(newBalance,0);
		assertEquals(rc.getJuiceFees(),BALANCE-newBalance);
		
		checkFinalState(rc,false);
	}

	protected void checkFinalState(ResultContext rc, boolean memUsed) {
		// Nothing should have gone wrong with total coin supply
		assertEquals(Coin.SUPPLY,rc.getState().computeTotalBalance());
		
		if (memUsed) {
			// we expect total memory to have fallen because of memory used
			long NEW_TOTAL_MEMORY=rc.getState().computeTotalMemory();
			assertTrue(rc.memUsed>0);
			assertLess(NEW_TOTAL_MEMORY,TOTAL_MEMORY);
			assertEquals(rc.memUsed,TOTAL_MEMORY-NEW_TOTAL_MEMORY);
		} else {
			// memory should not be created or destroyed
			assertEquals(TOTAL_MEMORY,rc.getState().computeTotalMemory());
		}
	}

	private ResultContext runTransaction(ATransaction t) {
		Context c=context();
		State s=c.getState();
		return s.applyTransaction(t);
	}
}
