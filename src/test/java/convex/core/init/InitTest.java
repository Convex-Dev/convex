package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.AccountStatus;
import convex.core.init.Init;
import convex.core.init.InitConfigTest;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.ACVMTest;

public class InitTest extends ACVMTest {

	protected InitTest() {
		super( Init.createState(InitConfigTest.create()));
	}

	@Test
	public void testInitState() throws InvalidDataException {
		INITIAL.validate();
		assertEquals(0,context().getDepth());
		assertNull(context().getResult());

		assertEquals(Constants.MAX_SUPPLY, INITIAL.computeTotalFunds());
	}

	@Test
	public void testMemoryExchange() {
		AccountStatus as=INITIAL.getAccount(Init.MEMORY_EXCHANGE_ADDRESS);
		assertNotNull(as);
		assertTrue(as.getMemory()>0L);
	}

	@Test
	public void testHero() {
        InitConfigTest initConfigTest = InitConfigTest.create();
		AccountStatus as=INITIAL.getAccount(InitConfigTest.HERO_ADDRESS);
		assertNotNull(as);
		assertEquals(Constants.INITIAL_ACCOUNT_ALLOWANCE,as.getMemory());
	}
}
