package convex.actors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.Init;
import convex.core.data.AHashMap;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Symbol;
import convex.core.lang.Context;
import convex.core.lang.TestState;

public class RegistryTest {

	@Test
	public void testRegistryContract() throws IOException {
		// TODO: think about whether we want this in initial state
		// String contractString=Utils.readResourceAsString("contracts/registry.con");
		// Object
		// cfn=CoreTest.INITIAL_CONTEXT.eval(Reader.read(contractString)).getResult();
		// Context<?> ctx=CoreTest.INITIAL_CONTEXT.deployContract(cfn);
		// Address addr=(Address) ctx.getResult();
		Address addr = Init.REGISTRY_ADDRESS;

		AHashMap<Keyword, Object> ddo = Maps.of(Keyword.create("name"), "Bob");
		Context<?> ctx = TestState.INITIAL_CONTEXT.actorCall(addr, 0, Symbol.create("register"), ddo);
		assertEquals(ddo, ctx.actorCall(addr, 0, "lookup", ctx.getAddress()).getResult());
	}

}
