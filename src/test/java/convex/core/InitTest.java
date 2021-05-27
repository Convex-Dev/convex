package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.AccountStatus;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.ACVMTest;

public class InitTest extends ACVMTest {
	
	protected InitTest() {
		super( Init.createState());
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
		AccountStatus as=INITIAL.getAccount(Init.MEMORY_EXCHANGE);
		assertNotNull(as);
		assertTrue(as.getMemory()>0L);
	}
	
	@Test 
	public void testHero() {
		AccountStatus as=INITIAL.getAccount(Init.HERO);
		assertNotNull(as);
		assertEquals(Constants.INITIAL_ACCOUNT_ALLOWANCE,as.getMemory());
	}
}
