package convex.core.lang;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.Constants;
import convex.core.cpos.CPoSConstants;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.ops.Lookup;
import convex.core.cvm.ops.Special;
import convex.core.data.Address;
import convex.core.data.Index;
import convex.core.data.Keywords;
import convex.core.data.Symbols;
import convex.core.data.prim.CVMLong;
import convex.core.init.BaseTest;
import convex.core.init.InitTest;

@TestInstance(Lifecycle.PER_CLASS)
public class SpecialTest extends ACVMTest {
	

	protected SpecialTest() throws IOException {
		super(BaseTest.STATE);
	}

	@Test
	public void testSpecialAddress() {
		// Hero should be *address* initial context
		assertEquals(InitTest.HERO, eval("*address*"));

		// *address* MUST return Actor address within actor call
		Context ctx=step("(def act (deploy `(do (defn addr ^{:callable true} [] *address*))))");
		Address act=(Address) ctx.getResult();
		assertEquals(act, eval(ctx,"(call act (addr))"));
		
		// *address* MUST be current address in library call
		assertEquals(InitTest.HERO, eval(ctx,"(act/addr)"));
	}
	
	@Test
	public void testSpecialOrigin() {
		// Hero should be *origin* in initial context
		assertEquals(InitTest.HERO, eval("*origin*"));
		
		// Origin should be preserved across query-as?
		assertEquals(InitTest.HERO, eval("(query-as #8 '*origin*)"));

		// *origin* MUST return original address within actor call
		Context ctx=step("(def act (deploy `(do (defn origin ^{:callable true} [] *origin*))))");
		assertEquals(InitTest.HERO, eval(ctx,"(call act (origin))"));
		
		// *origin* MUST be original address in library call
		assertEquals(InitTest.HERO, eval(ctx,"(act/origin)"));
	}
	
	@Test
	public void testSpecialKey() {
		assertEquals(InitTest.HERO_KEYPAIR.getAccountKey(), eval("*key*"));
	}
	
	@Test
	public void testSpecialJuice() {
		// TODO: semantics of returning juice before lookup complete is OK?
		// seems sensible, represents "juice left at this position".
		assertCVMEquals(0, eval(Special.forSymbol(Symbols.STAR_JUICE)));

		// juice gets consumed before returning a value
		assertCVMEquals(Juice.DO + Juice.CONSTANT, eval(comp("(do 1 *juice*)")));
	}
	
	@Test
	public void testSpecialJuiceLimit() {
		Special<CVMLong> spec=Special.forSymbol(Symbols.STAR_JUICE_LIMIT);
		
		// Juice limit at start of transaction
		assertCVMEquals(Constants.MAX_TRANSACTION_JUICE, eval(spec));

		// Consuming a small amount of juice shouldn't change limit
		Context ctx=step("1");
		assertCVMEquals(Constants.MAX_TRANSACTION_JUICE, eval(ctx,"*juice-limit*"));
	}

	@Test
	public void testSpecialJuicePrice() {
		Special<?> jp=Special.forSymbol(Symbols.STAR_JUICE_PRICE);
		assertNotNull(jp);
		assertCVMEquals(Constants.INITIAL_JUICE_PRICE, eval(jp));
		
		assertCVMEquals(Constants.INITIAL_JUICE_PRICE, eval("*juice-price*"));
		
		assertSame(context().getState().getJuicePrice(),eval("*juice-price*"));
	}
	
	@Test
	public void testSpecialPeer() {
		assertNull(eval("*peer*"));
	}
	
	@Test
	public void testSpecialSigned() {
		assertNull(eval("*signer*"));
	}

	@Test
	public void testSpecialAllowance() {
		// Should have initial allowance at start
		assertEquals(CPoSConstants.INITIAL_ACCOUNT_ALLOWANCE, evalL("*memory*"));
		
		// Buy some memory
		assertEquals(CPoSConstants.INITIAL_ACCOUNT_ALLOWANCE+1, evalL("(do (set-memory (inc *memory*)) *memory*)"));

		// Sell all memory
		assertEquals(0, evalL("(do (set-memory 0) *memory*)"));
	}
	
	@Test
	public void testSpecialMemoryPrice() {
		Context c=context();
		// Memory price matches state
		double price=evalD("*memory-price*");
		assertCVMEquals(c.getState().getMemoryPrice(), price);
		
		// Buy some memory, should increase price
		c=exec(c,"(set-memory (+ *memory* 10))");
		assertTrue(price<evalD(c,"*memory-price*"));
		
		// Check memory went down
		assertTrue(evalL(c,"*balance*")<HERO_BALANCE);
	}


	@Test
	public void testSpecialBalance() {
		// balance should return exact balance of account after execution
		Context ctx = step("(long *balance*)");
		Long bal=ctx.getAccountStatus(HERO).getBalance();
		assertCVMEquals(bal, ctx.getResult());

		// throwing it all away....
		assertEquals(666666L, evalL("(do (transfer "+VILLAIN+" (- *balance* 666666)) *balance*)"));

		// check balance as single expression
		assertEquals(bal, evalL("*balance*"));

		// Local values override specials
		assertNull(eval("(let [*balance* nil] *balance*)"));

		// TODO: reconsider this, special take priority over environment?
		assertCVMEquals(ctx.getOffer(),eval("(do (def *offer* :foo) *offer*)"));

		// Alternative behaviour
		//assertNull(eval("(let [*balance* nil] *balance*)"));
		//assertEquals(Keywords.FOO,eval("(do (def *balance* :foo) *balance*)"));
	}
	
	@Test
	public void testSpecialOverride() {
		// Should be possible to override specials in current environment
		assertEquals(CVMLong.ONE, eval("(let [*address* 1] *address*)"));
		
		// TODO: what should happen here?
		//assertEquals(eval("*address*"), eval("(do (def *address* 1) *address*)"));
	}


	@Test
	public void testSpecialCaller() {
		assertNull(eval("*caller*"));
		assertEquals(HERO, eval("(do (def c (deploy '(do (defn f ^{:callable true} [] *caller*)))) (call c (f)))"));
	}

	@Test
	public void testSpecialResult() {
		// initial context result should be null
		assertNull(eval("*result*"));

		// Result should get value of last completed expression
		assertEquals(Keywords.FOO, eval("(do :foo *result*)"));
		assertNull(eval("(do 1 (do) *result*)"));
		
		// TODO: how should this behave?
		// assertEquals(Keywords.FOO, eval("(let [a :foo] *result*)"));
		
		assertEquals(Keywords.FOO, eval("(do ((fn [] :foo)) *result*)"));

		// *result* should be cleared to nil in an Actor call.
		assertNull(eval("(do (def c (deploy '(do (defn f ^{:callable true} [] *result*)))) (call c (f)))"));
	}
	
	@Test
	public void testSpecialNop() {
		// initial context result should be null
		assertNull(eval("*nop*"));
		
		// *nop* should return *result*
		assertEquals(Keywords.FOO, eval("(do :foo *nop*)"));

		// Result should propagate through nop
		assertEquals(Keywords.FOO, eval("(do :foo *nop* *result*)"));
		assertNull(eval("(do 1 (do) *nop* *result*)"));


		// *result* should be cleared to nil in an Actor call.
		assertNull(eval("(do (def c (deploy '(do (defn f ^{:callable true} [] *nop*)))) (call c (f)))"));
	}

	@Test
	public void testSpecialState() {
		assertSame(INITIAL, eval("*state*"));
		assertSame(INITIAL.getAccounts(), eval("(:accounts *state*)"));
	}
	
	@Test
	public void testSpecialScope() {
		assertNull(eval("*scope*"));
	}
	
	@Test
	public void testSpecialEdgeCases() {

		// query-as interactions with *balance*
		assertEquals(0,evalL("(query-as #8 '*balance*)"));
		assertEquals(HERO_BALANCE,evalL("(query-as #8 '(query-as *caller* '*balance*))"));

		// Lookup in core environment of special returns the corresponding Special Op
		assertSame(Special.get("*juice*"),eval("(lookup *juice*)"));
		assertSame(Special.get("*juice*"),eval(Lookup.create(Symbols.STAR_JUICE)));

		assertNull(Special.get("*you-are-not-special*"));
	}

	@Test public void testSpecialHoldings() {
		assertSame(Index.none(),eval("*holdings*"));

		// Test set-holding modifies *holdings* as expected
		assertNull(eval("(get-holding *address*)"));
		assertEquals(Index.of(HERO,1L),eval("(do (set-holding *address* 1) *holdings*)"));

		assertNull(eval("(*holdings* { :PuSg 650989 })"));
		assertEquals(Keywords.FOO,eval("(*holdings* { :PuSg 650989 } :foo )"));
	}

}
