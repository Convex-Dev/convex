package convex.actors;

import static convex.test.Assertions.assertArgumentError;
import static convex.test.Assertions.assertStateError;
import static convex.test.Assertions.assertTrustError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Symbol;
import convex.core.init.BaseTest;
import convex.core.init.Init;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.test.Samples;

public class RegistryTest extends ACVMTest {

	protected RegistryTest() throws IOException {
		super(BaseTest.STATE);
	}

	static final Address REG = Init.REGISTRY_ADDRESS;

	@Test
	public void testRegistryContract() throws IOException {
		Context ctx = context();

		AHashMap<Keyword, ACell> ddo = Maps.of(Keyword.create("name"), "Bob");
		ctx = ctx.actorCall(REG, 0, Symbol.create("register"), ddo);
		assertEquals(ddo, ctx.actorCall(REG, 0, "lookup", ctx.getAddress()).getResult());
	}

	@Test
	public void testRegistryCNSUpdate() throws IOException {
		Context ctx = context().forkWithAddress(Init.GOVERNANCE_ADDRESS);
		ctx=exec(ctx,"(def ^:static reg #9)");

		assertNull(eval(ctx, "(resolve convex.test.foo)"));

		// Real Address we want for CNS mapping
		final Address badAddr = Samples.BAD_ADDRESS;

		ctx = step(ctx, "(reg/update 'convex.test.foo " + badAddr + ")");
		assertStateError(ctx);

		// Should fail, not a Symbol
		ctx = step(ctx, "(reg/update \"convex.test.foo\" #1)");
		assertArgumentError(ctx);

		final Address realAddr = Address.create(1); // Init address, FWIW
		ctx = exec(ctx, "(reg/create 'convex.test.foo " + realAddr + ")");

		assertEquals(realAddr, eval(ctx, "(reg/resolve 'convex.test.foo)"));

		{ // Check VILLAIN can't steal CNS mapping
			Context c = ctx.forkWithAddress(VILLAIN);

			// VILLAIN shouldn't be able to use update on existing CNS mapping
			assertTrustError(step(c, "(#9/update 'convex.test.foo *address*)"));

			// original mapping should be held
			assertEquals(realAddr, eval(c, "(#9/resolve 'convex.test.foo)"));
		}

		{ // Check Transfer of control to VILLAIN
			Context c = exec(ctx, "(reg/control 'convex.test.foo " + VILLAIN + ")");

			// This address shouldn't be able to use update or control any more
			assertTrustError(step(c, "(reg/update 'convex.test.foo *address*)"));
			assertTrustError(step(c, "(reg/control 'convex.test.foo *address*)"));

			// Switch to VILLAIN
			c = c.forkWithAddress(VILLAIN);

			// Change mapping
			c = exec(c, "(*registry*/update 'convex.test.foo *address*)");
			assertEquals(VILLAIN, eval(c, "(*registry*/resolve 'convex.test.foo)"));
		}

		{ // Check VILLAIN can create new mapping ?? TODO: where??

			//Context c = ctx.forkWithAddress(VILLAIN);

			// VILLAIN shouldn't be able to use update on existing CNS mapping
			//c = exec(c, "(*registry*/create 'convex.villain *address*)");

			// original mapping should be held
			// assertEquals(VILLAIN, eval(c, "(*registry*/resolve 'convex.villain)"));
		}
	}
}
