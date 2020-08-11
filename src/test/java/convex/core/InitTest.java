package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.AccountStatus;
import convex.core.exceptions.InvalidDataException;

public class InitTest {

	@Test
	public void testInitState() throws InvalidDataException {
		State s = Init.INITIAL_STATE;
		s.validate();
	}
	
	@Test 
	public void testMemoryExchange() {
		State s = Init.INITIAL_STATE;
		AccountStatus as=s.getAccount(Init.MEMORY_EXCHANGE);
		assertNotNull(as);
		assertTrue(as.getAllowance()>0L);
	}
	
	@Test 
	public void testHEro() {
		State s = Init.INITIAL_STATE;
		AccountStatus as=s.getAccount(Init.HERO);
		assertNotNull(as);
		assertEquals(0L,as.getAllowance());
	}
}
