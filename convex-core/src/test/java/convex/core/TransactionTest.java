package convex.core;

import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.Keywords;
import convex.core.cvm.State;
import convex.core.cvm.Symbols;
import convex.core.cvm.TransactionContext;
import convex.core.cvm.impl.InvalidBlockException;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Call;
import convex.core.cvm.transactions.Invoke;
import convex.core.cvm.transactions.Multi;
import convex.core.cvm.transactions.Transactions;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.RecordTest;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.Transaction;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.test.Samples;

/**
 * Tests for Transactions, especially when applied in isolation to a State
 */
public class TransactionTest extends ACVMTest {
	
	AKeyPair HERO_KP=InitTest.HERO_KEYPAIR;
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
		
		ResultContext rc=state().applyTransaction(t1);
		State s=rc.context.getState();
		long expectedFees=(Juice.TRANSACTION+Juice.TRANSFER+Juice.TRANSACTION_PER_BYTE*memSize)*JP;
		assertEquals(expectedFees,rc.getJuiceFees());
		
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
	public void testMultiRetainsChildLogsAndJuice() {
		// Regression: each child was forked into a fresh context, so the enclosing log
		// was replaced by the last child's log and juice restarted at zero per child
		Invoke t1=Invoke.create(HERO, 1, "(log 1)");
		Invoke t2=Invoke.create(HERO, 1, "(log 2)");
		ResultContext rc=INITIAL.applyTransaction(Multi.create(HERO, 1,Multi.MODE_ALL,t1,t2));
		Context rctx=rc.context;
		assertFalse(rctx.isError());

		AVector<AVector<ACell>> log=rctx.getLog();
		assertEquals(2,log.count());
		assertEquals(HERO,log.get(0).get(0));
		assertCVMEquals(Vectors.of(1L),log.get(0).get(3));
		assertEquals(HERO,log.get(1).get(0));
		assertCVMEquals(Vectors.of(2L),log.get(1).get(3));

		long j1=INITIAL.applyTransaction(Multi.create(HERO, 1,Multi.MODE_ALL,t1)).juiceUsed;
		long j2=INITIAL.applyTransaction(Multi.create(HERO, 1,Multi.MODE_ALL,t2)).juiceUsed;
		assertTrue(j1>0);
		assertEquals(j1+j2,rc.juiceUsed);
	}

	@Test
	public void testMultiControlledAccountOrigin() {
		// A child for a controlled account runs with that account as origin and
		// address, and its log entries are attributed to it
		State s=apply(Invoke.create(VILLAIN, 1, "(set-controller "+HERO+")"));
		Invoke t1=Invoke.create(HERO, 1, "(log *origin*)");
		Invoke t2=Invoke.create(VILLAIN, 1, "(log *origin*)");
		ResultContext rc=s.applyTransaction(Multi.create(HERO, 1,Multi.MODE_ALL,t1,t2));
		Context rctx=rc.context;
		assertFalse(rctx.isError());

		AVector<AVector<ACell>> log=rctx.getLog();
		assertEquals(2,log.count());
		assertEquals(HERO,log.get(0).get(0));
		assertCVMEquals(Vectors.of(HERO),log.get(0).get(3));
		assertEquals(VILLAIN,log.get(1).get(0));
		assertCVMEquals(Vectors.of(VILLAIN),log.get(1).get(3));
	}

	@Test
	public void testMultiTrustMonitorControl() {
		// The signer may act for a child origin whose controller is a trust monitor
		// that grants it :control, by the same rule as eval-as
		Context ctx=step("(deploy '(do (defn check-trusted? ^{:callable true} [s a o] (and (= s *scope*) (= a :control)))))");
		Address monitor=(Address) ctx.getResult();
		Transfer t=Transfer.create(VILLAIN, 1, HERO, 1000);

		// Scoped to HERO: HERO's signature covers a child for VILLAIN
		State trusted=stepAs(VILLAIN,ctx,"(set-controller ["+monitor+" "+HERO+"])").getState();
		long before=trusted.getAccount(VILLAIN).getBalance();
		ResultContext rc=trusted.applyTransaction(Multi.create(HERO, 1, Multi.MODE_ALL, t));
		assertFalse(rc.context.isError());
		assertEquals(before-1000,rc.context.getState().getAccount(VILLAIN).getBalance());

		// Scoped to VILLAIN only: HERO is denied
		State untrusted=stepAs(VILLAIN,ctx,"(set-controller ["+monitor+" "+VILLAIN+"])").getState();
		ResultContext denied=untrusted.applyTransaction(Multi.create(HERO, 1, Multi.MODE_ANY, t));
		assertFalse(denied.context.isError());
		AVector<Result> rs=denied.context.getResult();
		assertEquals(ErrorCodes.TRUST,rs.get(0).getErrorCode());
		assertEquals(before,denied.context.getState().getAccount(VILLAIN).getBalance());
	}

	private ATransaction nestMulti(ATransaction inner, int levels) {
		ATransaction t=inner;
		for (int i=0; i<levels; i++) t=Multi.create(HERO, 1, Multi.MODE_ANY, t);
		return t;
	}

	/** Follows single-child Multi results inward, returning the first error and its level */
	private static Result innermostError(Context rctx, int[] levelOut) {
		ACell r=rctx.getResult();
		int level=0;
		while (true) {
			AVector<?> rs=(AVector<?>) r;
			assertEquals(1,rs.count());
			Result inner=(Result) rs.get(0);
			level++;
			if (inner.isError()||!(inner.getValue() instanceof AVector)) {
				levelOut[0]=level;
				return inner;
			}
			r=inner.getValue();
		}
	}

	@Test
	public void testMultiNestingDepthBounded() {
		// Regression: nesting was never counted against the depth limit, so a few
		// thousand levels overflowed the stack during block application
		Transfer transfer=Transfer.create(HERO, 1, VILLAIN, 1);
		long before=INITIAL.getAccount(VILLAIN).getBalance();

		// Nesting up to the limit executes the innermost transaction
		ResultContext ok=INITIAL.applyTransaction(nestMulti(transfer,Constants.MAX_DEPTH));
		assertFalse(ok.context.isError());
		assertEquals(before+1,ok.context.getState().getAccount(VILLAIN).getBalance());

		// One level beyond fails with a DEPTH error at the innermost fork
		int[] level=new int[1];
		ResultContext limit=INITIAL.applyTransaction(nestMulti(transfer,Constants.MAX_DEPTH+1));
		assertFalse(limit.context.isError()); // MODE_ANY reports the child outcome
		assertEquals(ErrorCodes.DEPTH,innermostError(limit.context,level).getErrorCode());
		assertEquals(Constants.MAX_DEPTH+1,level[0]);
		assertEquals(before,limit.context.getState().getAccount(VILLAIN).getBalance());

		// Far deeper nesting is bounded the same way rather than overflowing the stack
		ResultContext deep=INITIAL.applyTransaction(nestMulti(transfer,3000));
		assertEquals(ErrorCodes.DEPTH,innermostError(deep.context,level).getErrorCode());
		assertEquals(Constants.MAX_DEPTH+1,level[0]);
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

		// A Call decoded from its encoding must execute identically. Regression:
		// decoded Calls NPEd on apply because the lazy args field was never populated.
		try {
			Call t3=(Call) convex.core.message.Message.create(Format.encodeMultiCell(t2,true)).getPayload(null);
			ResultContext rc3=s.applyTransaction(t3);
			assertCVMEquals(7,rc3.getResult());
		} catch (BadFormatException | convex.core.exceptions.PartialMessageException e) {
			throw new AssertionError("Call transaction failed to re-decode",e);
		}

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
	public void testBadSequence() throws BadSignatureException, InvalidBlockException {
		Invoke t1=Invoke.create(HERO, 2, "(+ 2 5)");
		SignedData<ATransaction> st = Samples.KEY_PAIR.signData(t1);
		ResultContext rc=state().applyTransaction(st,TransactionContext.create(state()));
		Context ctx=rc.context;
		assertEquals(ErrorCodes.SEQUENCE,ctx.getError().getCode());
		
		// check source correctly identified
		assertEquals(SourceCodes.CVM, rc.source);

		
		// Sequence number in state should be unchanged
		assertEquals(0L,ctx.getAccountStatus(HERO).getSequence());
		
		doTransactionTests(t1);
	}
	
	/**
	 * Tests for transactions that don't get as far as code execution
	 */
	@SuppressWarnings("unchecked")
	@Test public void testBadSignedTransactions() throws InvalidBlockException {
		State s=state();
		AccountStatus as=s.getAccount(HERO);
		long SEQ=as.getSequence()+1;
		TransactionContext tctx=TransactionContext.create(s);
		
		{ // wrong sequence
			ResultContext rc=s.applyTransaction(HERO_KP.signData(Invoke.create(HERO, SEQ+1,Keywords.FOO)),tctx);
			assertEquals(ErrorCodes.SEQUENCE,rc.getErrorCode());
			checkNoTransactionEffects(s,rc);
		}

		{ // non-existent account
			ResultContext rc=s.applyTransaction(HERO_KP.signData(Invoke.create(Address.create(777777), SEQ,Keywords.FOO)),tctx);
			assertEquals(ErrorCodes.NOBODY,rc.getErrorCode());
			checkNoTransactionEffects(s,rc);
		}
		
		{ // wrong key 
			ResultContext rc=s.applyTransaction(VILLAIN_KP.signData(Invoke.create(HERO, SEQ,Keywords.FOO)),tctx);
			assertEquals(ErrorCodes.SIGNATURE,rc.getErrorCode());
			checkNoTransactionEffects(s,rc);
		}
		
		{ // account without public key
			ResultContext rc=s.applyTransaction(HERO_KP.signData(Invoke.create(Address.ZERO, SEQ,Keywords.FOO)),tctx);
			assertEquals(ErrorCodes.STATE,rc.getErrorCode());
			checkNoTransactionEffects(s,rc);
		}
		
		{
			// signed something other than a transaction
			@SuppressWarnings("rawtypes")
			SignedData<ATransaction> st = (SignedData)HERO_KP.signData(Keywords.FOO);
			assertThrows(InvalidBlockException.class, ()->s.applyTransaction(st,tctx));
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
	public void testJuiceFail() throws InvalidBlockException {
		State s=state();
		AccountStatus as=s.getAccount(HERO);
		long SEQ=as.getSequence()+1;
		SignedData<ATransaction> st=HERO_KP.signData(Invoke.create(HERO, SEQ,"(loop [] (def a 2) (recur))"));
		ResultContext rc=s.applyTransaction(st,TransactionContext.create(s));
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
		doTransactionTests(Transfer.create(HERO, 199, VILLAIN,Coin.MAX_SUPPLY));
		
		doTransactionTests(Call.create(HERO, 99, VILLAIN, 178,Symbols.FOO,Vectors.empty()));
	}

	private void doTransactionTests(ATransaction t) {
		assertEquals(VILLAIN,t.withOrigin(VILLAIN).getOrigin());
		assertEquals(999999,t.withSequence(999999).getSequence());
		
		assertEquals(Transaction.INSTANCE,t.getType());
		
		RecordTest.doRecordTests(t);
	}
	
	

}
