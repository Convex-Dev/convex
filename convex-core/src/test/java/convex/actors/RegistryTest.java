package convex.actors;

import static convex.test.Assertions.assertArgumentError;
import static convex.test.Assertions.assertNobodyError;
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
	public void testRegistryCNS() throws IOException {
		Context ctx = context();

		assertEquals(REG, eval(ctx, "(call *registry* (cns-resolve 'convex.registry))"));
	}

	@Test
	public void testRegistryCNSUpdate() throws IOException {
		Context ctx = context();

		assertNull(eval(ctx, "(call *registry* (cns-resolve 'convex.test.foo))"));

		// Real Address we want for CNS mapping
		final Address badAddr = Samples.BAD_ADDRESS;

		ctx = step(ctx, "(call *registry* (cns-update 'convex.test.foo " + badAddr + "))");
		assertNobodyError(ctx);

		// Should fail, not a Symbol
		ctx = step(ctx, "(call *registry* (cns-update \"convex.test.foo\" #1))");
		assertArgumentError(ctx);

		final Address realAddr = Address.create(1); // Init address, FWIW
		ctx = exec(ctx, "(call *registry* (cns-update 'convex.test.foo " + realAddr + "))");

		assertEquals(realAddr, eval(ctx, "(call *registry* (cns-resolve 'convex.test.foo))"));

		{ // Check VILLAIN can't steal CNS mapping
			Context c = ctx.forkWithAddress(VILLAIN);

			// VILLAIN shouldn't be able to use update on existing CNS mapping
			assertTrustError(step(c, "(call *registry* (cns-update 'convex.test.foo *address*))"));

			// original mapping should be held
			assertEquals(realAddr, eval(c, "(call *registry* (cns-resolve 'convex.test.foo))"));
		}

		{ // Check Transfer of control to VILLAIN
			Context c = exec(ctx, "(call *registry* (cns-control 'convex.test.foo " + VILLAIN + "))");

			// HERO shouldn't be able to use update or control any more
			assertTrustError(step(c, "(call *registry* (cns-update 'convex.test.foo *address*))"));
			assertTrustError(step(c, "(call *registry* (cns-control 'convex.test.foo *address*))"));

			// Switch to VILLAIN
			c = c.forkWithAddress(VILLAIN);

			// Change mapping
			c = exec(c, "(call *registry* (cns-update 'convex.test.foo *address*))");
			assertEquals(VILLAIN, eval(c, "(call *registry* (cns-resolve 'convex.test.foo))"));
		}

		{ // Check VILLAIN can create new mapping
			// TODO probably shouldn't be free-for-all?

			Context c = ctx.forkWithAddress(VILLAIN);

			// VILLAIN shouldn't be able to use update on existing CNS mapping
			c = exec(c, "(call *registry* (cns-update 'convex.villain *address*))");

			// original mapping should be held
			assertEquals(VILLAIN, eval(c, "(call *registry* (cns-resolve 'convex.villain))"));
		}
	}
}
