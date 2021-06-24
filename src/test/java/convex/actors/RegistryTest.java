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
import convex.core.init.InitConfigTest;
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

		AHashMap<Keyword, ACell> ddo = Maps.of(Keyword.create("name"), "Bob");
		Context<?> ctx = INITIAL_CONTEXT.actorCall(REG, 0, Symbol.create("register"), ddo);
		assertEquals(ddo, ctx.actorCall(REG, 0, "lookup", ctx.getAddress()).getResult());
	}

	@Test
	public void testRegistryCNS() throws IOException {
		Context<?> ctx=INITIAL_CONTEXT.fork();

		assertEquals(REG,eval(ctx,"(call *registry* (cns-resolve :convex.registry))"));
	}

	@Test
	public void testRegistryCNSUpdate() throws IOException {
		Context<?> ctx=INITIAL_CONTEXT.fork();

		assertNull(eval(ctx,"(call *registry* (cns-resolve :convex.test.foo))"));

		// Real Address we want for CNS mapping
		final Address realAddr=Samples.BAD_ADDRESS;

		ctx=step(ctx,"(call *registry* (cns-update :convex.test.foo "+realAddr+"))");
		assertEquals(realAddr,eval(ctx,"(call *registry* (cns-resolve :convex.test.foo))"));

		{ // Check VILLAIN can't steal CNS mapping
			Context<?> c=ctx.forkWithAddress(InitConfigTest.VILLAIN_ADDRESS);

			// VILLAIN shouldn't be able to use update on existing CNS mapping
			assertTrustError(step(c,"(call *registry* (cns-update 'convex.test.foo *address*))"));

			// original mapping should be held
			assertEquals(realAddr,eval(c,"(call *registry* (cns-resolve :convex.test.foo))"));
		}

		{ // Check Transfer of control to VILLAIN
			Context<?> c=step(ctx,"(call *registry* (cns-control 'convex.test.foo "+InitConfigTest.VILLAIN_ADDRESS+"))");

			// HERO shouldn't be able to use update or control any more
			assertTrustError(step(c,"(call *registry* (cns-update 'convex.test.foo *address*))"));
			assertTrustError(step(c,"(call *registry* (cns-control 'convex.test.foo *address*))"));

			// Switch to VILLAIN
			c=c.forkWithAddress(InitConfigTest.VILLAIN_ADDRESS);

			// Change mapping
			c=step(c,"(call *registry* (cns-update 'convex.test.foo *address*))");
			assertNotError(c);
			assertEquals(InitConfigTest.VILLAIN_ADDRESS,eval(c,"(call *registry* (cns-resolve :convex.test.foo))"));
		}

		{ // Check VILLAIN can create new mapping
			// TODO probably shouldn't be free-for-all?

			Context<?> c=ctx.forkWithAddress(InitConfigTest.VILLAIN_ADDRESS);

			// VILLAIN shouldn't be able to use update on existing CNS mapping
			c=step(c,"(call *registry* (cns-update :convex.villain *address*))");
			assertNotError(c);

			// original mapping should be held
			assertEquals(InitConfigTest.VILLAIN_ADDRESS,eval(c,"(call *registry* (cns-resolve :convex.villain))"));
		}
	}
}
