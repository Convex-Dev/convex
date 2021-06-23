package convex.core.init;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.State;
import convex.core.data.AccountStatus;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.ACVMTest;

/**
 * Tests for Init functionality
 * 
 * Also includes static State instances for Testing
 */
public class InitTest extends ACVMTest {

	public static final State STATE=Init.createState(InitConfigTest.create());
	public static final State BASE = Init.createBaseAccounts(InitConfigTest.create());
	public static final State CORE = Init.createCoreLibraries(InitConfigTest.create());
	
	protected InitTest() {
		super(STATE);
	}

	@Test
	public void testInitState() throws InvalidDataException {
		STATE.validate();
		assertEquals(0,context().getDepth());
		assertNull(context().getResult());

		assertEquals(Constants.MAX_SUPPLY, STATE.computeTotalFunds());
	}

	@Test
	public void testMemoryExchange() {
		AccountStatus as=STATE.getAccount(Init.MEMORY_EXCHANGE_ADDRESS);
		assertNotNull(as);
		assertTrue(as.getMemory()>0L);
	}

	@Test
	public void testHero() {
 		AccountStatus as=STATE.getAccount(InitConfigTest.HERO_ADDRESS);
		assertNotNull(as);
		assertEquals(Constants.INITIAL_ACCOUNT_ALLOWANCE,as.getMemory());
	}
}
