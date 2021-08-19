package convex.actors;

import static convex.test.Assertions.assertNotError;
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
import convex.core.init.Init;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.test.Samples;

public class RegistryTest extends ACVMTest {

	protected RegistryTest() throws IOException {
		super(InitTest.BASE);
	}

	static final Address REG = Init.REGISTRY_ADDRESS;

	Context<?> INITIAL_CONTEXT=context();

	@Test
	public void testRegistryContract() throws IOException {
		// TODO: think about whether we want this in initial state
		// String contractString=Utils.readResourceAsString("contracts/registry.con");
		// Object
		// cfn=CoreTest.INITIAL_CONTEXT.eval(Reader.read(contractString)).getResult();
		// Context<?> ctx=CoreTest.INITIAL_CONTEXT.deployContract(cfn);
		// Address addr=(Address) ctx.getResult();


		assertEquals(42L, evalL("(do (def a (deploy '(*registry*/meta.set 42))) (*registry*/meta.get a))"));
	}

	@Test
	public void testRegistryCNS() throws IOException {
		Context<?> ctx=INITIAL_CONTEXT.fork();

		assertEquals(REG,eval(ctx,"(*registry*/cns.resolve 'convex.registry)"));
	}

	@Test
	public void testRegistryCNSUpdate() throws IOException {
		Context<?> ctx=INITIAL_CONTEXT.fork();

		assertNull(eval(ctx,"(*registry*/cns.resolve 'convex.test.foo)"));

		// Real Address we want for CNS mapping
		final Address realAddr=Samples.BAD_ADDRESS;

		ctx=step(ctx,"(*registry*/cns.update 'convex.test.foo "+realAddr+")");
		assertEquals(realAddr,eval(ctx,"(*registry*/cns.resolve 'convex.test.foo)"));

		{ // Check VILLAIN can't steal CNS mapping
			Context<?> c=ctx.forkWithAddress(VILLAIN);

			// VILLAIN shouldn't be able to use update on existing CNS mapping
			assertTrustError(step(c,"(*registry*/cns.update 'convex.test.foo *address*)"));

			// original mapping should be held
			assertEquals(realAddr,eval(c,"(*registry*/cns.resolve 'convex.test.foo)"));
		}

		{ // Check Transfer of control to VILLAIN
			Context<?> c=step(ctx,"(*registry*/cns.control 'convex.test.foo "+VILLAIN+")");

			// HERO shouldn't be able to use update or control any more
			assertTrustError(step(c,"(*registry*/cns.update 'convex.test.foo *address*)"));
			assertTrustError(step(c,"(*registry*/cns.control 'convex.test.foo *address*)"));

			// Switch to VILLAIN
			c=c.forkWithAddress(VILLAIN);

			// Change mapping
			c=step(c,"(*registry*/cns.update 'convex.test.foo *address*)");
			assertNotError(c);
			assertEquals(VILLAIN,eval(c,"(*registry*/cns.resolve 'convex.test.foo)"));
		}

		{ // Check VILLAIN can create new mapping
			// TODO probably shouldn't be free-for-all?

			Context<?> c=ctx.forkWithAddress(VILLAIN);

			// VILLAIN shouldn't be able to use update on existing CNS mapping
			c=step(c,"(*registry*/cns.update 'convex.villain *address*)");
			assertNotError(c);

			// original mapping should be held
			assertEquals(VILLAIN,eval(c,"(*registry*/cns.resolve 'convex.villain)"));
		}
	}
}
