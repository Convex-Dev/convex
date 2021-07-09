package convex.actors;

import static convex.test.Assertions.assertAssertError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Keywords;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.RT;
import convex.core.lang.Reader;

public class OracleTest extends ACVMTest {

	@SuppressWarnings("rawtypes")
	@Test
	public void testOracleActor() throws IOException {

		// setup address for this scene
		Context ctx =step("(do (def HERO" + HERO + ") (def VILLAIN " + VILLAIN + "))");

		ACell contractCode = Reader.readResource("actors/oracle-trusted.con");
		ctx = ctx.deployActor(contractCode);

		Address oracle3 = (Address) ctx.getResult();
		String o3_str = oracle3.toHexString();
		ctx=step(ctx,"(def oracle3 (address 0x"+o3_str+"))");

		// register an oracle entry owned by our hero
		ctx = step(ctx, "(call oracle3 (register :foo {:trust #{HERO}}))");
		assertTrue(RT.bool(ctx.getResult()));

		assertFalse(evalB(ctx, "(call oracle3 (finalised? :foo))"));
		assertNull(eval(ctx, "(call oracle3 (read :foo))"));

		{
			// some tests for Actor safety pre-setting
			final Context<?> fctx = Context.createFake(ctx.getState(), VILLAIN);
			assertFalse(fctx.isExceptional());
			assertFalse(evalB(fctx, "(call (address \"" + o3_str + "\") (finalised? :foo))"));
			assertAssertError(step(fctx, "(call (address \"" + o3_str + "\") (provide :foo :bad-value))"));
		}

		// finalise the oracle
		ctx = step(ctx, "(call oracle3 (provide :foo :bar))");
		assertTrue(evalB(ctx, "(call oracle3 (finalised? :foo))"));
		assertEquals(Keywords.BAR, eval(ctx, "(call oracle3 (read :foo))"));

		// try to update after finalisation
		ctx = step(ctx, "(call oracle3 (provide :foo :late-value))");
		assertEquals(Keywords.BAR, eval(ctx, "(call oracle3 (read :foo))"));
	}
}
