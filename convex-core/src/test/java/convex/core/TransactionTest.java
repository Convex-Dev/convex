package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.AVector;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Cells;
import convex.core.data.Keywords;
import convex.core.data.RecordTest;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.Transaction;
import convex.core.exceptions.BadSignatureException;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Symbols;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Call;
import convex.core.transactions.Invoke;
import convex.core.transactions.Multi;
import convex.core.transactions.Transactions;
import convex.core.transactions.Transfer;
import convex.test.Samples;

import static convex.test.Assertions.*;

/**
 * Tests for Transactions, especially when applied in isolation to a State
 */
public class TransactionTest extends ACVMTest {
	
	Address HERO=InitTest.HERO;
	AKeyPair HERO_KP=InitTest.HERO_KEYPAIR;
	
	Address VILLAIN=InitTest.VILLAIN;
	AKeyPair VILLAIN_KP=InitTest.VILLAIN_KEYPAIR;
	long JP=Constants.INITIAL_JUICE_PRICE;
	
	protected State state() {
		return context().getState();
	}
	
	protected State apply(ATransaction t) {
		State s=state();
		Context ctx= s.applyTransaction(t).context;
		assertNotError(ctx);
		return ctx.getState();
	}
	
	@Test 
	public void testTransfer() {
		long AMT=999;
		long IBAL=state().getAccount(HERO).getBalance();
		Transfer t1=Transfer.create(HERO, 1, VILLAIN, AMT);
		
		long memSize=Cells.storageSize(t1);
		
		State s=apply(t1);
		long expectedFees=(Juice.TRANSACTION+Juice.TRANSFER+Juice.TRANSACTION_PER_BYTE*memSize)*JP;
		assertEquals(expectedFees,s.getGlobalFees().longValue());
		
		long NBAL=s.getAccount(HERO).getBalance();
		long balanceDrop=IBAL-NBAL;
		assertEquals(AMT+expectedFees,balanceDrop);
		
		// We expect a Transfer to be completely encoded
		assertTrue(Cells.isCompletelyEncoded(t1));
		
		doTransactionTests(t1);
	}
	
	@Test public void testJSON() {
		Invoke tx=Invoke.create(VILLAIN, 1, "(+ 2 3)");
		HashMap<String,Object> hm=Transactions.toJSON(tx);
		assertEquals("Invoke",hm.get("type"));
		assertEquals(1L,hm.get("sequence"));
	}   
	
	@Test 
	public void testMulti() {
		Transfer t1=Transfer.create(HERO, 120, VILLAIN, 1000);
		Transfer t2=Transfer.create(HERO, 140, VILLAIN, 2000);
		Multi m1=Multi.create(HERO, 1,Multi.MODE_ALL,t1,t2);
		State s=apply(m1);
		long gain=s.getAccount(VILLAIN).getBalance()-this.INITIAL.getAccount(VILLAIN).getBalance();
		assertEquals(3000,gain);
	
		doTransactionTests(m1);
	}
	
	@Test 
	public void testMulti_MODE_ANY() {
		Transfer t1=Transfer.create(HERO, 120, VILLAIN, 1000);
		Transfer t2=Transfer.create(VILLAIN, 140, HERO, 2000);
		Multi m1=Multi.create(HERO, 1,Multi.MODE_ANY,t1,t2);
		ResultContext rc=INITIAL.applyTransaction(m1);
		Context rctx=rc.context;
		assertFalse(rctx.isError());
		AVector<Result> rs=rctx.getResult();
		assertEquals(2,rs.count());
		Result r1=rs.get(0);
		Result r2=rs.get(1);
		assertNull(r1.getErrorCode());
		assertEquals(ErrorCodes.TRUST,r2.getErrorCode());
		
		State s=rctx.getState();
		long gain=s.getAccount(VILLAIN).getBalance()-this.INITIAL.getAccount(VILLAIN).getBalance();
		assertEquals(1000,gain);
	
		doTransactionTests(m1);
	}
	
	@SuppressWarnings("unchecked")
	@Test 
	public void testMulti_MODE_ALL() {
		Transfer t1=Transfer.create(HERO, 120, VILLAIN, 1000);
		Transfer t2=Transfer.create(VILLAIN, 140, HERO, 2000);
		Multi m1=Multi.create(HERO, 1,Multi.MODE_ALL,t1,t2);
		ResultContext rc=INITIAL.applyTransaction(m1);
		Context rctx=rc.context;
		assertTrue(rctx.isError());
		assertEquals(ErrorCodes.CHILD,rctx.getErrorCode());
		AVector<Result> rs=(AVector<Result>) rctx.getExceptional().getMessage();
		assertEquals(2,rs.count());
		Result r1=rs.get(0);
		Result r2=rs.get(1);
		assertNull(r1.getErrorCode());
		assertEquals(ErrorCodes.TRUST,r2.getErrorCode());
		
		State s=rctx.getState();
		long gain=s.getAccount(VILLAIN).getBalance()-this.INITIAL.getAccount(VILLAIN).getBalance();
		assertEquals(0,gain);
	
		doTransactionTests(m1);
	}
	
	@Test 
	public void testMulti_MODE_FIRST() {
		Transfer t1=Transfer.create(VILLAIN, 120, VILLAIN, 1000);
		Transfer t2=Transfer.create(HERO, 140, VILLAIN, 2000);
		Multi m1=Multi.create(HERO, 1,Multi.MODE_FIRST,t1,t2);
		ResultContext rc=INITIAL.applyTransaction(m1);
		Context rctx=rc.context;
		assertFalse(rctx.isError());
		AVector<Result> rs=rctx.getResult();
		assertEquals(2,rs.count());
		Result r1=rs.get(0);
		Result r2=rs.get(1);
		assertEquals(ErrorCodes.TRUST,r1.getErrorCode());
		assertNull(r2.getErrorCode());
		
		State s=rctx.getState();
		long gain=s.getAccount(VILLAIN).getBalance()-this.INITIAL.getAccount(VILLAIN).getBalance();
		assertEquals(2000,gain);
	
		doTransactionTests(m1);
	}
	
	@Test 
	public void testMulti_MODE_UNTIL() {
		Transfer t1=Transfer.create(HERO, 120, VILLAIN, 1000);
		Transfer t2=Transfer.create(VILLAIN, 140, VILLAIN, 2000);
		Transfer t3=Transfer.create(VILLAIN, 160, VILLAIN, 4000);
		Multi m1=Multi.create(HERO, 1,Multi.MODE_UNTIL,t1,t2,t3);
		ResultContext rc=INITIAL.applyTransaction(m1);
		Context rctx=rc.context;
		assertFalse(rctx.isError());
		AVector<Result> rs=rctx.getResult();
		assertEquals(2,rs.count());
		Result r1=rs.get(0);
		Result r2=rs.get(1);
		assertNull(r1.getErrorCode());
		assertEquals(ErrorCodes.TRUST,r2.getErrorCode());
		
		State s=rctx.getState();
		long gain=s.getAccount(VILLAIN).getBalance()-this.INITIAL.getAccount(VILLAIN).getBalance();
		assertEquals(1000,gain);
	
		doTransactionTests(m1);
	}
	
	@Test 
	public void testCall() {
		State s=state();
		Call t1=Call.create(HERO, 1, HERO, Symbols.FOO, Vectors.empty());
		// should fail with no callable function
		ResultContext rc=state().applyTransaction(t1);
		assertEquals(ErrorCodes.STATE,rc.getErrorCode());
		
		Context ctx=context();
		ctx=exec(ctx,"(deploy '(defn ^:callable foo [] 7))");
		s=ctx.getState();
		Address target=ctx.getResult();
		
		Call t2=Call.create(HERO, 1, target, Symbols.FOO, Vectors.empty());
		ResultContext rc2=s.applyTransaction(t2);
		assertCVMEquals(7,rc2.getResult());

		
		// We expect a short call transaction to be completely encoded
		assertTrue(Cells.isCompletelyEncoded(t1));
		
		doTransactionTests(t1);
		doTransactionTests(t2);
	}
	
	@Test 
	public void testInvoke() {
		Invoke t1=Invoke.create(HERO, 1, "(+ 2 5)");
		ResultContext rc=state().applyTransaction(t1);
		Context ctx=rc.context;
		assertEquals(CVMLong.create(7),ctx.getResult());
		
		// We expect a short Invoke to be completely encoded
		assertTrue(Cells.isCompletelyEncoded(t1));
		
		doTransactionTests(t1);
	}
	
	@Test 
	public void testBadSequence() throws BadSignatureException {
		Invoke t1=Invoke.create(HERO, 2, "(+ 2 5)");
		SignedData<Invoke> st = Samples.KEY_PAIR.signData(t1);
		ResultContext rc=state().applyTransaction(st);
		Context ctx=rc.context;
		assertEquals(ErrorCodes.SEQUENCE,ctx.getError().getCode());
		
		// check source correctly identified
		assertEquals(SourceCodes.CVM, rc.source);

		
		// Sequence number in state should be unchanged
		assertEquals(0L,ctx.getAccountStatus(HERO).getSequence());
		
		doTransactionTests(t1);
	}
	
	/**
	 * TEsts for transactions that don't get as far as code execution
	 */
	@Test public void testBadSignedTransactions() {
		State s=state();
		AccountStatus as=s.getAccount(HERO);
		long SEQ=as.getSequence()+1;
		
		{ // wrong sequence
			ResultContext rc=s.applyTransaction(HERO_KP.signData(Invoke.create(HERO, SEQ+1,Keywords.FOO)));
			assertEquals(ErrorCodes.SEQUENCE,rc.getErrorCode());
			checkNoTransactionEffects(s,rc);
		}

		{ // non-existent account
			ResultContext rc=s.applyTransaction(HERO_KP.signData(Invoke.create(Address.create(777777), SEQ,Keywords.FOO)));
			assertEquals(ErrorCodes.NOBODY,rc.getErrorCode());
			checkNoTransactionEffects(s,rc);
		}
		
		{ // wrong key 
			ResultContext rc=s.applyTransaction(VILLAIN_KP.signData(Invoke.create(HERO, SEQ,Keywords.FOO)));
			assertEquals(ErrorCodes.SIGNATURE,rc.getErrorCode());
			checkNoTransactionEffects(s,rc);
		}
		
		{ // account without public key
			ResultContext rc=s.applyTransaction(HERO_KP.signData(Invoke.create(Address.ZERO, SEQ,Keywords.FOO)));
			assertEquals(ErrorCodes.STATE,rc.getErrorCode());
			checkNoTransactionEffects(s,rc);
		}
	}
	
	private void checkNoTransactionEffects(State s, ResultContext rc) {
		Context ctx=rc.context;
		
		// No change to state would be sufficient, but object identity means we are being more efficient so test for that
		assertEquals(s,rc.getState());
		assertSame(s,rc.getState());
		
		// If :CODE Result source, something should have changed in State
		assertNotEquals(SourceCodes.CODE,rc.getSource());
		
		// No juice should have been used
		assertEquals(0,ctx.getJuiceUsed());
		assertEquals(0,rc.juiceUsed);
		assertEquals(0,rc.memUsed);
	}
	
	@Test 
	public void testJuiceFail() {
		State s=state();
		AccountStatus as=s.getAccount(HERO);
		long SEQ=as.getSequence()+1;
		SignedData<ATransaction> st=HERO_KP.signData(Invoke.create(HERO, SEQ,"(loop [] (def a 2) (recur))"));
		ResultContext rc=s.applyTransaction(st);
		assertEquals(ErrorCodes.JUICE,rc.getErrorCode());
		assertEquals(SourceCodes.CVM,rc.getSource());
		assertSame(st.getValue(),rc.tx);
		
		// Note there will be state effects because juice got consumed 
		//checkNoTransactionEffects(s,rc);
	}

	@Test public void testBigValues() {
		// Checks in case there are oddities with big values / VLC encoding
		doTransactionTests(Invoke.create(HERO, 99, "(+ 2 5)"));
		doTransactionTests(Invoke.create(HERO, 199, "(+ 2 5)"));
		doTransactionTests(Invoke.create(HERO, 677599, "(+ 2 5)"));
		
		// 99 chosen to be outside 1-byte VLC Long range
		doTransactionTests(Transfer.create(HERO, 99, VILLAIN,1000));
		doTransactionTests(Transfer.create(HERO, 199, VILLAIN,Coin.SUPPLY));
		
		doTransactionTests(Call.create(HERO, 99, VILLAIN, 178,Symbols.FOO,Vectors.empty()));
	}

	private void doTransactionTests(ATransaction t) {
		assertEquals(VILLAIN,t.withOrigin(VILLAIN).getOrigin());
		assertEquals(999999,t.withSequence(999999).getSequence());
		
		assertEquals(Transaction.INSTANCE,t.getType());
		
		RecordTest.doRecordTests(t);
	}
	
	

}
