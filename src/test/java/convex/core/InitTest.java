package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.AccountStatus;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Core;

public class InitTest {
	State s = Init.STATE;
	
	@Test
	public void testInitState() throws InvalidDataException {
		s.validate();
	}
	
	@Test 
	public void testMemoryExchange() {
		AccountStatus as=s.getAccount(Init.MEMORY_EXCHANGE);
		assertNotNull(as);
		assertTrue(as.getAllowance()>0L);
	}
	
	@Test public void testCoreAccount() {
		assertEquals(Init.CORE_ACCOUNT,s.getAccount(Core.CORE_ADDRESS));
	}
	
	@Test 
	public void testHEro() {
		AccountStatus as=s.getAccount(Init.HERO);
		assertNotNull(as);
		assertEquals(0L,as.getAllowance());
	}
}
