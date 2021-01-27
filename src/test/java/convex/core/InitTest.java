package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.AccountStatus;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.TestState;

public class InitTest {
	State s = Init.STATE;
	
	@Test
	public void testInitState() throws InvalidDataException {
		s.validate();
		assertEquals(0,TestState.INITIAL_CONTEXT.getDepth());
		assertNull(TestState.INITIAL_CONTEXT.getResult());
		
		assertNotNull(Init.FIRST_PEER_KEY);
	}
	
	@Test 
	public void testMemoryExchange() {
		AccountStatus as=s.getAccount(Init.MEMORY_EXCHANGE);
		assertNotNull(as);
		assertTrue(as.getAllowance()>0L);
	}
	
	@Test public void testCoreAccount() {
		// core environment should be the same as when first created
		assertEquals(Init.CORE_ACCOUNT.getEnvironment(),s.getAccount(Init.CORE_ADDRESS).getEnvironment());
	}
	
	@Test 
	public void testHero() {
		AccountStatus as=s.getAccount(Init.HERO);
		assertNotNull(as);
		assertEquals(Constants.INITIAL_ACCOUNT_ALLOWANCE,as.getAllowance());
	}
}
