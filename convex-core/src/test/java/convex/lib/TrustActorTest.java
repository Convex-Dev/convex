package convex.lib;

import static convex.test.Assertions.assertArgumentError;
import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;

public class TrustActorTest extends ACVMTest {
	
	protected Address trusted;

	protected TrustActorTest() throws IOException {
		super(InitTest.STATE);
	}

	@Override protected Context buildContext(Context ctx) {
		String importS = "(import convex.trust :as trust)";
		ctx = exec(ctx, importS);
		trusted = (Address)ctx.getResult();
		return ctx;
	}
	
	@Test void testMonitors() {
		Context ctx=exec(context(),"(import convex.trust.monitors :as mon)");
		
		// Permit-subjects
		assertTrue(evalB(ctx,"(trust/trusted? (mon/permit-subjects #3 #14 #17) #14)"));
		assertFalse(evalB(ctx,"(trust/trusted? (mon/permit-subjects #3 #14 #17) #15)"));
		
		// Permit actions
		assertTrue(evalB(ctx,"(trust/trusted? (mon/permit-actions :foo :bar) #14 :foo)"));
		assertTrue(evalB(ctx,"(trust/trusted? (mon/permit-actions :foo :bar nil) #14 nil)"));
		assertFalse(evalB(ctx,"(trust/trusted? (mon/permit-actions :foo :bar) #14 :baz :foo)"));
		assertFalse(evalB(ctx,"(trust/trusted? (mon/permit-actions :foo :bar) #14 nil)"));
		assertFalse(evalB(ctx,"(trust/trusted? (mon/permit-actions) #14 nil)"));
		
		// Delegate [allow deny base]
		assertTrue(evalB(ctx,"(trust/trusted? (mon/delegate #3 #4 #5) #3)"));
		assertFalse(evalB(ctx,"(trust/trusted? (mon/delegate #3 #4 #5) #33)"));
		assertTrue(evalB(ctx,"(trust/trusted? (mon/delegate #3 #4 #5) #5)"));
		assertFalse(evalB(ctx,"(trust/trusted? (mon/delegate #3 #4 #5) nil)"));
		
		// any 
		assertTrue(evalB(ctx,"(trust/trusted? (mon/any #3 #4 #5) #4)"));
		assertTrue(evalB(ctx,"(trust/trusted? (mon/any #3 #4 #5) #3)"));
		assertFalse(evalB(ctx,"(trust/trusted? (mon/any #3 #4 #5) #8)"));

		// all
		assertTrue(evalB(ctx,"(trust/trusted? (mon/all #3 #3) #3)"));
		assertFalse(evalB(ctx,"(trust/trusted? (mon/all #3 #4 #5) #3)"));
		assertTrue(evalB(ctx,"(trust/trusted? (mon/all #3 (mon/any #3 #4)) #3)"));
		
		// rule
		assertTrue(evalB(ctx,"(trust/trusted? (mon/rule (fn [s a o] true)) #3)"));
		assertFalse(evalB(ctx,"(trust/trusted? (mon/rule (fn [s a o] false)) #3)"));
		
	}
	
	@Test
	public void testDelegate() {
		Context ctx=exec(context(),"(import convex.trust.delegate :as del)");
		Address del=ctx.getResult();
		
		// Unscoped usage
		assertArgumentError(step(ctx,"(trust/trusted? del *address*)"));
		
		// Missing ID
		assertFalse(evalB(ctx,"(trust/trusted? [del -1] *address*)"));
		
		// Get a monitor
		ctx=exec(ctx,"(call del (create nil))");
		ACell id = ctx.getResult();
		AVector<ACell> mon=Vectors.of(del,id);
		assertFalse(evalB(ctx,"(trust/trusted? "+mon+" *address*)"));
		
		// Update monitor to own address
		ctx=exec(ctx,"(call "+mon+" (update *address*))");
		
		assertFalse(evalB(ctx,"(trust/trusted? "+mon+" #0)"));
		assertTrue(evalB(ctx,"(trust/trusted? "+mon+" *address*)"));
		
		// Get another monitor
		ctx=exec(ctx,"(call del (create nil))");
		ACell newid = ctx.getResult();
		assertNotEquals(id,newid);

		TrustTest.testChangeControl(ctx, mon);
	}
	
	@Test
	public void testWhitelist() {
		Context ctx=step(context(),"(import convex.trust.whitelist :as allow)");
		assertNotError(ctx);
		
		assertArgumentError(step(ctx,"(trust/trusted? allow *address*)"));
		assertArgumentError(step(ctx,"(trust/trusted? [allow nil] *address*)"));
		
		assertTrue(evalB(ctx,"(trust/trusted? [allow #{*address*}] *address*)"));
		assertFalse(evalB(ctx,"(trust/trusted? [allow #{*address*}] #0)"));
		assertTrue(evalB(ctx,"(trust/trusted? [allow #{#0 #1}] #0)"));
		
		// A subject that is definitely not an address
		assertFalse(evalB(ctx,"(trust/trusted? [allow #{*address*}] :not-an-address)"));
	}
}
