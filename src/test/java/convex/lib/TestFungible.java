package convex.lib;

import static convex.core.lang.TestState.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.lang.Context;
import convex.core.lang.Reader;
import convex.core.lang.TestState;
import convex.core.util.Utils;

import static convex.test.Assertions.*;

public class TestFungible {
	private static final Symbol fSym=Symbol.create("fun-actor");
	
	private static Context<?> loadFungible() {
		Context<?> ctx=TestState.INITIAL_CONTEXT;
		try {
			ctx=ctx.deployActor(Reader.read(Utils.readResourceAsString("libraries/fungible.con")), true);
			Address fun=(Address) ctx.getResult();
			String importS="(import "+fun+" :as fungible)";
			ctx=step(ctx,importS);
			assertFalse(ctx.isExceptional());
			
			ctx=ctx.define(fSym, Syntax.create(fun));
		} catch (IOException e) {
			throw new Error(e);
		}
		
		return ctx;
	}
	
	private static final Context<?> ctx=loadFungible();
	private static final Address fungible=(Address) ctx.lookup(fSym).getResult();
	
	/**
	 * Test that re-deployment of Fungible matches what is expected
	 */
	@Test public void testLibraryProperties() {
		assertTrue(ctx.getAccountStatus(fungible).isActor());
		assertEquals(fungible,TestState.CON_FUNGIBLE);
		
		assertEquals("Fungible Library",eval("(:name (call *registry* (lookup "+fungible+")))").toString());
	}
	
	@Test public void testBuildToken() {
		// check our alias is right
		Context<?> ctx=TestFungible.ctx;
		assertEquals(fungible,eval(ctx,"(get *aliases* 'fungible)"));
		
		// deploy a token with default config
		ctx=step(ctx,"(def token (deploy (fungible/build-token {})))");
		Address token = (Address) ctx.getResult();
		assertTrue(ctx.getAccountStatus(token)!=null);
		ctx=step(ctx,"(def token (address "+token+"))");
		
		// check our balance is positive as initial holder
		Long bal=eval(ctx,"(fungible/balance token *address*)");
		assertTrue(bal>0);
		
		// transfer to the Villain scenario
		{
			Context<?> tctx=step(ctx,"(fungible/transfer token "+TestState.VILLAIN+" 100)");
			assertEquals(bal-100,(long)eval(tctx,"(fungible/balance token *address*)"));
			assertEquals(100,(long)eval(tctx,"(fungible/balance token "+TestState.VILLAIN+")"));
		}
		
		// acceptable transfers
		assertNotError(step(ctx,"(fungible/transfer token *address* 0)"));
		assertNotError(step(ctx,"(fungible/transfer token *address* "+bal+")"));
		
		// bad transfers
		assertAssertError(step(ctx,"(fungible/transfer token *address* -1)"));
		assertAssertError(step(ctx,"(fungible/transfer token *address* "+(bal+1)+")"));
	}
	
	@Test public void testMint() {
		// check our alias is right
		Context<?> ctx=TestFungible.ctx;
		
		// deploy a token with default config
		ctx=step(ctx,"(def token (deploy [(fungible/build-token {:supply 100}) (fungible/add-mint {:max-supply 1000})]))");
		Address token = (Address) ctx.getResult();
		assertTrue(ctx.getAccountStatus(token)!=null);
		
		// check our balance is positive as initial holder
		Long bal=eval(ctx,"(fungible/balance token *address*)");
		assertEquals(100L,bal);
		
		// Mint up to max and back down to zero
		{
			Context<?> c=step(ctx,"(fungible/mint token 900)");
			assertEquals(1000L,evalL(c,"(fungible/balance token *address*)"));
	
			c=step(c,"(fungible/mint token -900)");
			assertEquals(bal,evalL(c,"(fungible/balance token *address*)"));

			c=step(c,"(fungible/mint token -100)");
			assertEquals(0L,evalL(c,"(fungible/balance token *address*)"));
		}
		
		// Illegal Minting amounts
		{
			assertError(step(ctx,"(fungible/mint token 901)")); // too much
			assertError(step(ctx,"(fungible/mint token -101)")); // too little
		}
		
		// Villain shouldn't be able to mint
		{
			Context<?> c=ctx.switchAddress(VILLAIN);
			c=step(c,"(def token "+token+")");
			c=step(c,"(import convex.fungible :as fungible)");
			
			assertTrustError(step(c,"(fungible/mint token 100)"));
			assertTrustError(step(c,"(fungible/mint token 10000)")); // trust before amount checks
		}
	}
}
