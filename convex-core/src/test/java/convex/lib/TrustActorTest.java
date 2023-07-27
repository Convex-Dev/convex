package convex.lib;

import static convex.test.Assertions.assertArgumentError;
import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Vectors;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;

public class TrustActorTest extends ACVMTest {
	
	protected Address trusted;

	protected TrustActorTest() throws IOException {
		super(InitTest.STATE);
	}

	@Override protected Context buildContext(Context ctx) {
		String importS = "(import convex.trust :as trust)";
		ctx = step(ctx, importS);
		assertNotError(ctx);
		trusted = (Address)ctx.getResult();
		return ctx;
	}
	
	@Test
	public void testDelegate() {
		Context ctx=step(context(),"(import convex.trust.delegate :as del)");
		assertNotError(ctx);
		Address del=ctx.getResult();
		
		// Unscoped usage
		assertArgumentError(step(ctx,"(trust/trusted? del *address*)"));
		
		// Missing ID
		assertFalse(evalB(ctx,"(trust/trusted? [del -1] *address*)"));
		
		// Get a monitor
		ctx=step(ctx,"(call del (create nil))");
		ACell id = ctx.getResult();
		AVector<ACell> mon=Vectors.of(del,id);
		assertFalse(evalB(ctx,"(trust/trusted? "+mon+" *address*)"));
		
		// Update monitor to own address
		ctx=step(ctx,"(call "+mon+" (update *address*))");
		assertNotError(ctx);
		
		assertFalse(evalB(ctx,"(trust/trusted? "+mon+" #0)"));
		assertTrue(evalB(ctx,"(trust/trusted? "+mon+" *address*)"));

		TrustTest.testChangeControl(ctx, mon);
	}
}
