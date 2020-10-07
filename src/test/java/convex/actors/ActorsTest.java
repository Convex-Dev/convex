package convex.actors;

import static convex.core.lang.TestState.eval;
import static convex.core.lang.TestState.step;
import static convex.test.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.lang.Context;
import convex.core.lang.Core;
import convex.core.lang.Symbols;
import convex.core.lang.TestState;
import convex.core.util.Utils;

public class ActorsTest {

	@Test public void testDeployAndCall() {
		Context<?> ctx=TestState.step("(def caddr (deploy '(let [n 10] (defn getter [] n) (defn hidden [] nil) (defn plus [x] (+ n x)) (export getter plus))))");
		
		assertEquals(Address.class,ctx.getResult().getClass());
		
		assertEquals(10L,(long)eval(ctx,"(call caddr (getter))"));
		assertEquals(14L,(long)eval(ctx,"(call caddr (plus 4))"));
		
		assertFalse((boolean)eval(ctx,"(exports? caddr 'foo)"));
		assertTrue((boolean)eval(ctx,"(exports? caddr 'getter)"));
		
		assertStateError(step(ctx,"(call caddr (bad-symbol 2))"));
		assertStateError(step(ctx,"(call caddr (hidden 2))"));
		assertArityError(step(ctx,"(call caddr (getter 2))"));
		assertArityError(step(ctx,"(call caddr (plus))"));
		assertFundsError(step(ctx,"(call caddr 1000000000000000000 (plus))"));
		assertCastError(step(ctx,"(call caddr (plus :foo))"));
	}
	
	@Test public void testSimpleDeploys() {
		assertTrue((boolean)eval("(address? (deploy 1))"));
	}
	
	@Test public void testDeployFailures() {
		assertArityError(step("(deploy)"));
		assertArityError(step("(deploy 1 2)")); 

		assertArityError(step("(deploy '(if))"));
	}
	
	@Test public void testNotActor() {
		assertFalse((boolean)eval("(actor? *address*)"));
		assertFalse((boolean)eval("(exports? *address* 'foo)"));
		assertStateError(TestState.step("(call *address* (not-a-function))"));
	}
	
	@Test public void testMinimalContract() {
		Context<?> ctx=TestState.step("(def caddr (deploy '(do)))");
		Address a=(Address) ctx.getResult();
		assertNotNull(a);

		assertFalse((boolean)eval(ctx,"(exports? caddr 'foo)"));
		
		assertEquals(Core.COUNT,ctx.lookup(Symbols.COUNT).getValue());
		assertNull(ctx.getAccountStatus(a).getEnvironmentValue(Symbols.FOO));
	}
	
	@Test public void testTokenContract() throws IOException {
		String VILLAIN=TestState.VILLAIN.toHexString();
		String HERO=TestState.HERO.toHexString();
		
		// setup address for this scene
		Context<?> ctx=TestState.step("(do (def HERO (address \""+HERO+"\")) (def VILLAIN (address \""+VILLAIN+"\")))");
		
		// Technique of constructing a contract using a String
		String contractString=Utils.readResourceAsString("contracts/token.con");
		ctx=TestState.step(ctx,"(def my-token (deploy ("+contractString+" 101 1000 HERO)))"); // contract initialisation args
		
		assertEquals(1000L,(long)eval(ctx,"(call my-token (balance *address*))"));
		assertEquals(0L,(long)eval(ctx,"(call my-token (balance VILLAIN))"));
		ctx=TestState.step(ctx,"(call my-token (transfer VILLAIN 10))");
		ctx=TestState.step(ctx,"(call my-token (transfer HERO 100))"); // should have no effect
		final Context<?> fctx=ctx; // save context for later tests
		
		assertEquals(990L,(long)eval(fctx,"(call my-token (balance *address*))"));
		assertEquals(10L,(long)eval(fctx,"(call my-token (balance VILLAIN))"));
		
		assertEquals(1000L,(long)eval(fctx,"(call my-token (total-supply))"));

		assertTrue((boolean)eval(fctx,"(actor? my-token)"));
		assertFalse((boolean)eval(fctx,"(actor? HERO)"));
		assertFalse((boolean)eval(fctx,"(actor? :foo)"));

		// some tests for contract safety
		assertAssertError(TestState.step(fctx,"(call my-token (transfer VILLAIN 1000))"));
		assertAssertError(TestState.step(fctx,"(call my-token (transfer VILLAIN -1))"));
		assertAssertError(TestState.step(fctx,"(call my-token (transfer nil 10))"));
		assertStateError(TestState.step(fctx,"(call my-token (bad-function))"));
	}
	

	@Test public void testHelloContract() throws IOException {
		Context<?> ctx=TestState.step("(do )");
		
		// Technique for deploying contract with a quoted form
		String contractString=Utils.readResourceAsString("contracts/hello.con");
		ctx=TestState.step(ctx,"(def hello (deploy (quote "+contractString+")))"); 
		
		ctx=TestState.step(ctx,"(call hello (greet \"Nikki\"))");
		assertEquals("Hello Nikki",ctx.getResult().toString());
		
		ctx=TestState.step(ctx,"(call hello (greet \"Nikki\"))");
		assertEquals("Welcome back Nikki",ctx.getResult().toString());
		
		ctx=TestState.step(ctx,"(call hello (greet \"Alice\"))");
		assertEquals("Hello Alice",ctx.getResult().toString());
		
	}
	
	@Test public void testFundingContract() throws IOException {
		Context<?> ctx=TestState.step("(do )");
		Address addr=ctx.getAddress();
		long initialBalance=ctx.getBalance(addr);
		
		String contractString=Utils.readResourceAsString("contracts/funding.con");
		ctx=TestState.step(ctx,"(def funcon (deploy '"+contractString+"))");
		assertFalse(ctx.isExceptional());
		Address caddr=(Address) ctx.getResult();
		
		{
			// just test return of the correct *offer* value
			ctx=TestState.step(ctx,"(call funcon 1234 (echo-offer))");
			assertEquals(1234,(long)ctx.getResult());
			assertEquals(initialBalance,ctx.getBalance(addr));
		} 
		
		{
			// test accepting half of funds
			final Context<?> rctx=TestState.step(ctx,"(call funcon 1000 (accept-quarter))");
			assertEquals(250,(long)rctx.getResult());
			assertEquals(250,rctx.getBalance(caddr));
			
			assertEquals(initialBalance-250,rctx.getBalance(addr));
			assertEquals(TestState.TOTAL_FUNDS,rctx.getState().computeTotalFunds());
		}
		
		{
			// test accepting all funds
			final Context<?> rctx=TestState.step(ctx,"(call funcon 1237 (accept-all))");
			assertEquals(1237,(long)rctx.getResult());
			assertEquals(1237,rctx.getBalance(caddr));
			
			assertEquals(initialBalance-1237,rctx.getBalance(addr));
			assertEquals(TestState.TOTAL_FUNDS,rctx.getState().computeTotalFunds());
		}
		
		{
			// test accepting zero funds
			final Context<?> rctx=TestState.step(ctx,"(call funcon 1237 (accept-zero))");
			assertEquals(0,(long)rctx.getResult());
			assertEquals(0,rctx.getBalance(caddr));
			
			assertEquals(initialBalance,rctx.getBalance(addr));
			assertEquals(TestState.TOTAL_FUNDS,rctx.getState().computeTotalFunds());
		}
		
		{
			// test contract that accepts funds then rolls back
			final Context<?> rctx=TestState.step(ctx,"(call funcon 1237 (accept-rollback))");
			assertEquals(Keywords.FOO,rctx.getResult());
			assertEquals(0,rctx.getBalance(caddr));
			assertEquals(0,rctx.getOffer());
			
			assertEquals(initialBalance,rctx.getBalance(addr));
			assertEquals(TestState.TOTAL_FUNDS,rctx.getState().computeTotalFunds());
		}
		
		{
			// test contract that accepts funds repeatedly
			final Context<?> rctx=TestState.step(ctx,"(call funcon 1337 (accept-repeat))");
			assertEquals(0,(long)rctx.getResult()); // final offer echoed back
			assertEquals(1337,rctx.getBalance(caddr));
			
			assertEquals(initialBalance-1337,rctx.getBalance(addr));
			assertEquals(TestState.TOTAL_FUNDS,rctx.getState().computeTotalFunds());
		}
		
		{
			// test contract that forwards funds to self
			final Context<?> rctx=TestState.step(ctx,"(call funcon 1337 (accept-forward))");
			assertEquals(1337,(long)rctx.getResult()); // result of forward to accept-all
			assertEquals(1337,rctx.getBalance(caddr));
			
			assertEquals(initialBalance-1337,rctx.getBalance(addr));
			assertEquals(TestState.TOTAL_FUNDS,rctx.getState().computeTotalFunds());
		}
		
		// test *offer* restored after send
		assertEquals(0,(long)eval(ctx,"(do (call funcon 1237 (accept-zero)) *offer*)"));
		
		// test *offer* in contract with no send
		assertEquals(0,(long)eval(ctx,"(call funcon (echo-offer))"));
	}
	
	@Test public void testExceptionContract() throws IOException {
		Context<?> ctx=TestState.step("(do )");
		
		String contractString=Utils.readResourceAsString("contracts/exceptional.con");
		ctx=TestState.step(ctx,"(def ex (deploy '"+contractString+"))"); 
		
		ctx=TestState.step(ctx,"(call ex (halt-fn \"Jenny\"))");
		assertEquals("Jenny",ctx.getResult().toString());

		// calling this will break the fragile definition, but then rollback to restore it
		ctx=TestState.step(ctx,"(call ex (rollback-fn \"Alice\"))");
		assertEquals("Alice",ctx.getResult().toString());

		ctx=TestState.step(ctx,"(call ex (get-fragile))");
		assertEquals(Keyword.create("ok"),ctx.getResult());
		
		// Calling this should break the fragile definition permanently
		ctx=TestState.step(ctx,"(call ex (break-fn \"Lana\"))");
		assertEquals("Lana",ctx.getResult().toString());

		ctx=TestState.step(ctx,"(call ex (get-fragile))");
		assertEquals(Keyword.create("broken"),ctx.getResult());

	}
	
}
