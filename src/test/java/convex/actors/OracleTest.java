package convex.actors;

import static convex.core.lang.TestState.eval;
import static convex.core.lang.TestState.step;
import static convex.test.Assertions.assertAssertError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.data.Keywords;
import convex.core.lang.Context;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.lang.TestState;

public class OracleTest {

	@Test
	public void testOracleActor() throws IOException {
		String VILLAIN = TestState.VILLAIN.toHexString();
		String HERO = TestState.HERO.toHexString();

		// setup address for this scene
		Context<?> ctx = TestState
				.step("(do (def HERO (address \"" + HERO + "\")) (def VILLAIN (address \"" + VILLAIN + "\")))");

		Object contractCode = Reader.readResource("actors/oracle-trusted.con");
		ctx = ctx.deployActor(contractCode, true); 
		
		Address oracle3 = (Address) ctx.getResult();
		String o3_str = oracle3.toHexString();
		ctx=step(ctx,"(def oracle3 (address 0x"+o3_str+"))");

		// register an oracle entry owned by our hero
		ctx = TestState.step(ctx, "(call oracle3 (register :foo {:trust #{HERO}}))"); 
		assertTrue(RT.bool(ctx.getResult()));

		assertFalse((boolean) eval(ctx, "(call oracle3 (finalised? :foo))"));
		assertNull(eval(ctx, "(call oracle3 (read :foo))"));

		{
			// some tests for Actor safety pre-setting
			final Context<?> fctx = Context.createInitial(ctx.getState(), TestState.VILLAIN, TestState.INITIAL_JUICE);
			assertFalse(fctx.isExceptional());
			assertFalse((boolean) eval(fctx, "(call (address \"" + o3_str + "\") (finalised? :foo))"));
			assertAssertError(TestState.step(fctx, "(call (address \"" + o3_str + "\") (provide :foo :bad-value))"));
		}

		// finalise the oracle
		ctx = TestState.step(ctx, "(call oracle3 (provide :foo :bar))");
		assertTrue((boolean) eval(ctx, "(call oracle3 (finalised? :foo))"));
		assertEquals(Keywords.BAR, eval(ctx, "(call oracle3 (read :foo))"));

		// try to update after finalisation
		ctx = TestState.step(ctx, "(call oracle3 (provide :foo :late-value))");
		assertEquals(Keywords.BAR, eval(ctx, "(call oracle3 (read :foo))"));
	}
}
