package convex.actors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.cvm.Context;
import convex.core.lang.ACVMTest;
import convex.core.util.Utils;

import static convex.test.Assertions.*;

@TestInstance(Lifecycle.PER_CLASS)
public class DistributorTest extends ACVMTest {
	
	@Override protected Context buildContext(Context ctx) {
		try {
			String source=Utils.readResourceAsString("/convex/lab/distributor.cvx");
			
			ctx=exec(ctx,"(def DIST (deploy `(do "+source+")))");
			ctx=exec(ctx,"(import convex.trust :as trust)");
				
			return ctx;
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
		
	}

	
	@Test public void testInitialState() {
		Context c=context();
		
		assertEquals(0L,evalL(c,"DIST/available-coins"));
		
		assertFundsError(step(c,"(call DIST (distribute *address* 1))"));
		assertArgumentError(step(c,"(call DIST (distribute *address* nil))"));
		assertArgumentError(step(c,"(call DIST (distribute *address* -1))"));
		assertCastError(step(c,"(call DIST (distribute :foo 0))"));
		
		// initially no actor balance, so can't set available coins greater than 0
		assertStateError(step(c,"(call DIST (set-available 1))"));

		// zero distribution is OK
		c=exec(c,"(call DIST (distribute *address* 0))");

	}
	
	@Test public void testDistributuion() {
		Context c=context();

		// set available coins works after transferring in some coins
		c=exec(c,"(transfer DIST 3000000)");
		c=exec(c,"(call DIST (set-available 1000000))");
		assertEquals(1000000L,evalL(c,"DIST/available-coins"));
		
		// too large a distribution
		assertFundsError(step(c,"(call DIST (distribute *address* 1000001))"));
		
		// zero distribution is OK
		c=exec(c,"(call DIST (distribute *address* 0))");

		// Distribute 300,000
		c=exec(c,"(call DIST (distribute *address* 300000))");
		assertEquals(2700000L,evalL(c,"(balance DIST)"));
		assertEquals(700000L,evalL(c,"DIST/available-coins"));

	}
}
