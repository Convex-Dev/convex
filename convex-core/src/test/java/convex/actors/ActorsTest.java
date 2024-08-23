package convex.actors;

import static convex.test.Assertions.assertArityError;
import static convex.test.Assertions.assertAssertError;
import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertCastError;
import static convex.test.Assertions.assertFundsError;
import static convex.test.Assertions.assertStateError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.Core;
import convex.core.lang.Symbols;
import convex.core.util.Utils;

public class ActorsTest extends ACVMTest {

	@Test public void testDeployAndCall() {
		Context ctx=step("(def caddr (deploy '(let [n 10] (defn getter ^{:callable true} [] n) (defn hidden [] nil) (defn plus ^{:callable true} [x] (+ n x)))))");

		assertEquals(Address.class,ctx.getResult().getClass());

		assertEquals(10L,evalL(ctx,"(call caddr (getter))"));
		assertEquals(14L,evalL(ctx,"(call caddr (plus 4))"));

		assertFalse(evalB(ctx,"(callable? caddr 'foo)"));
		assertTrue(evalB(ctx,"(callable? caddr 'getter)"));

		assertStateError(step(ctx,"(call caddr (bad-symbol 2))"));
		assertStateError(step(ctx,"(call caddr (hidden 2))"));
		assertArityError(step(ctx,"(call caddr (getter 2))"));
		assertArityError(step(ctx,"(call caddr (plus))"));
		assertFundsError(step(ctx,"(call caddr 1000000000000000000 (plus))"));
		assertCastError(step(ctx,"(call caddr (plus :foo))"));
	}

	@Test public void testSimpleDeploys() {
		assertTrue(evalB("(address? (deploy 1))"));
	}

	@Test public void testDeployFailures() {
		assertArityError(step("(deploy)"));
		//assertArityError(step("(deploy 1 2)"));

		// Arity error during deployment
		assertArityError(step("(deploy '(if))"));
	}

	@Test public void testUserAsActor() {
		Context ctx=step("(do (defn foo ^{:callable true} [] *caller*) (defn bar [] nil) (def z 1))");
		assertEquals(InitTest.HERO,eval(ctx,"(call *address* (foo))"));
		assertStateError(step(ctx,"(call *address* (non-existent-function))"));
		assertStateError(step(ctx,"(call *address* (bar))"));
		assertStateError(step(ctx,"(call *address* (z))"));
	}

	@Test public void testNotActor() {
		assertFalse(evalB("(actor? *address*)"));
		assertFalse(evalB("(callable? *address* 'foo)"));
		assertStateError(step("(call *address* (not-a-function))"));
	}

	@Test public void testMinimalContract() {
		Context ctx=step("(def caddr (deploy '(do)))");
		Address a=(Address) ctx.getResult();
		assertNotNull(a);

		assertFalse(evalB(ctx,"(callable? caddr 'foo)"));

		assertEquals(Core.COUNT,ctx.lookup(Symbols.COUNT).getValue());
		assertNull(ctx.getAccountStatus(a).getEnvironmentValue(Symbols.FOO));
	}

	@Test public void testTokenContract() throws IOException {
		// setup address for this scene
		Context ctx=exec(context(),"(do (def HERO "+HERO+") (def VILLAIN "+VILLAIN+"))");

		// Technique of constructing a contract using a String
		String contractString=Utils.readResourceAsString("/contracts/token.con");
		ctx=exec(ctx,"(def my-token (deploy ("+contractString+" 101 1000 HERO)))"); // contract initialisation args

		assertEquals(1000L,evalL(ctx,"(call my-token (balance *address*))"));
		assertEquals(0L,evalL(ctx,"(call my-token (balance VILLAIN))"));
		ctx=step(ctx,"(call my-token (transfer VILLAIN 10))");
		ctx=step(ctx,"(call my-token (transfer HERO 100))"); // should have no effect
		final Context fctx=ctx; // save context for later tests

		assertEquals(990L,evalL(fctx,"(call my-token (balance *address*))"));
		assertEquals(10L,evalL(fctx,"(call my-token (balance VILLAIN))"));

		assertEquals(1000L,evalL(fctx,"(call my-token (total-supply))"));

		assertTrue(evalB(fctx,"(actor? my-token)"));
		assertFalse(evalB(fctx,"(actor? HERO)"));
		assertFalse(evalB(fctx,"(actor? :foo)"));

		// some tests for contract safety
		assertAssertError(step(fctx,"(call my-token (transfer VILLAIN 1000))"));
		assertAssertError(step(fctx,"(call my-token (transfer VILLAIN -1))"));
		assertAssertError(step(fctx,"(call my-token (transfer nil 10))"));
		assertStateError(step(fctx,"(call my-token (bad-function))"));
	}


	@Test public void testHelloContract() throws IOException {
		Context ctx=step("(do )");

		// Technique for deploying contract with a quoted form
		String contractString=Utils.readResourceAsString("/contracts/hello.con");
		ctx=step(ctx,"(def hello (deploy (quote "+contractString+")))");

		ctx=step(ctx,"(call hello (greet \"Nikki\"))");
		assertEquals("Hello Nikki",ctx.getResult().toString());

		ctx=step(ctx,"(call hello (greet \"Nikki\"))");
		assertEquals("Welcome back Nikki",ctx.getResult().toString());

		ctx=step(ctx,"(call hello (greet \"Alice\"))");
		assertEquals("Hello Alice",ctx.getResult().toString());

	}

	@Test public void testFundingContract() throws IOException {
		Context ctx=step("(do )");
		long TOTAL_FUNDS=Constants.MAX_SUPPLY;

		Address addr=ctx.getAddress();

		String contractString=Utils.readResourceAsString("/contracts/funding.con");
		ctx=step(ctx,"(def funcon (deploy '"+contractString+"))");
		assertFalse(ctx.isExceptional());
		Address caddr=(Address) ctx.getResult();
		long initialBalance=ctx.getBalance(addr);

		{
			// just test return of the correct *offer* value
			ctx=step(ctx,"(call funcon 1234 (echo-offer))");
			assertCVMEquals(1234,ctx.getResult());
			assertEquals(initialBalance,ctx.getBalance(addr));
			assertEquals(TOTAL_FUNDS,ctx.getState().computeTotalFunds());
		}

		{
			// test accepting half of funds
			final Context rctx=step(ctx,"(call funcon 1000 (accept-quarter))");
			assertCVMEquals(250,rctx.getResult());
			assertEquals(250,rctx.getBalance(caddr));

			assertEquals(initialBalance-250,rctx.getBalance(addr));
			assertEquals(TOTAL_FUNDS,rctx.getState().computeTotalFunds());
		}

		{
			// test accepting all funds
			final Context rctx=step(ctx,"(call funcon 1237 (accept-all))");
			assertCVMEquals(1237,rctx.getResult());
			assertEquals(1237,rctx.getBalance(caddr));

			assertEquals(initialBalance-1237,rctx.getBalance(addr));
			assertEquals(TOTAL_FUNDS,rctx.getState().computeTotalFunds());
		}

		{
			// test accepting zero funds
			final Context rctx=step(ctx,"(call funcon 1237 (accept-zero))");
			assertCVMEquals(0,rctx.getResult());
			assertEquals(0,rctx.getBalance(caddr));

			assertEquals(initialBalance,rctx.getBalance(addr));
			assertEquals(TOTAL_FUNDS,rctx.getState().computeTotalFunds());
		}

		{
			// test contract that accepts funds then rolls back
			final Context rctx=step(ctx,"(call funcon 1237 (accept-rollback))");
			assertEquals(Keywords.FOO,rctx.getResult());
			assertEquals(0,rctx.getBalance(caddr));
			assertEquals(0,rctx.getOffer());

			assertEquals(initialBalance,rctx.getBalance(addr));
			assertEquals(TOTAL_FUNDS,rctx.getState().computeTotalFunds());
		}

		{
			// test contract that accepts funds repeatedly
			final Context rctx=step(ctx,"(call funcon 1337 (accept-repeat))");
			assertCVMEquals(0,rctx.getResult()); // final offer echoed back
			assertEquals(1337,rctx.getBalance(caddr));

			assertEquals(initialBalance-1337,rctx.getBalance(addr));
			assertEquals(TOTAL_FUNDS,rctx.getState().computeTotalFunds());
		}

		{
			// test contract that forwards funds to self
			final Context rctx=step(ctx,"(call funcon 1337 (accept-forward))");
			assertCVMEquals(1337,rctx.getResult()); // result of forward to accept-all
			assertEquals(1337,rctx.getBalance(caddr));

			assertEquals(initialBalance-1337,rctx.getBalance(addr));
			assertEquals(TOTAL_FUNDS,rctx.getState().computeTotalFunds());
		}

		// test *offer* restored after send
		assertEquals(0,evalL(ctx,"(do (call funcon 1237 (accept-zero)) *offer*)"));

		// test *offer* in contract with no send
		assertEquals(0,evalL(ctx,"(call funcon (echo-offer))"));
	}



	@Test public void testExceptionContract() throws IOException {
		Context ctx=step("(do )");

		String contractString=Utils.readResourceAsString("/contracts/exceptional.con");
		ctx=exec(ctx,"(def ex (deploy '"+contractString+"))");

		ctx=exec(ctx,"(call ex (halt-fn \"Jenny\"))");
		assertEquals("Jenny",ctx.getResult().toString());

		// calling this will break the fragile definition, but then rollback to restore it
		ctx=exec(ctx,"(call ex (rollback-fn \"Alice\"))");
		assertEquals("Alice",ctx.getResult().toString());

		ctx=exec(ctx,"(call ex (get-fragile))");
		assertEquals(Keyword.create("ok"),ctx.getResult());

		// Calling this should break the fragile definition permanently
		ctx=exec(ctx,"(call ex (break-fn \"Lana\"))");
		assertEquals("Lana",ctx.getResult().toString());

		ctx=exec(ctx,"(call ex (get-fragile))");
		assertEquals(Keyword.create("broken"),ctx.getResult());

	}

}
