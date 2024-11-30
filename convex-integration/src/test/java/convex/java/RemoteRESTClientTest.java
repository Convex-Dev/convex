package convex.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.junit.Assume;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;

public class RemoteRESTClientTest {
	
	// Use to skip remote tests
	static boolean skip=true;
	
	static final String TEST_PEER="https://convex.world";
	
	public Convex getNewConvex() {
		if (skip) return null;
		AKeyPair kp=AKeyPair.generate();
		try {
			Convex convex=Convex.connect(TEST_PEER);
			Address addr=convex.createAccount(kp);
			convex.setAddress(addr);
			convex.setKeyPair(kp);
			return convex;
		} catch (Exception e) {
			skip=true;
			return null;
		}
			
	}
	
	@Test public void testQuery() {
		Convex convex=getNewConvex();
		checkValid(convex);
		Map<String,Object> result=convex.query ("*address*");
		assertNotNull(result);
		assertEquals(convex.getAddress(),Address.parse(result.get("value")));
	}
	
	@Test public void testQueryAccount() {
		Convex convex=getNewConvex();
		checkValid(convex);
		Map<String,Object> result=convex.queryAccount(convex.getAddress());
		assertNotNull(result);
		assertTrue(result.containsKey("sequence"));
		assertTrue(result.containsKey("memorySize"));
	}
	
	
	@Test public void testQueryAsync() throws InterruptedException, ExecutionException {
		Convex convex=getNewConvex();
		checkValid(convex);
		Future<Map<String,Object>> f=convex.queryAsync ("(+ 1 2)");
		Map<String,Object> result=f.get();
		assertNotNull(result);
		assertEquals(3L,result.get("value"));
	}
	
	@Test public void testTransact() {
		Convex convex=getNewConvex();
		checkValid(convex);
		convex.faucet(convex.getAddress(), 1000000);
		checkValid(convex);
		Map<String,Object> result=convex.transact ("(* 3 4)");
		assertNotNull(result);
		assertEquals(12L,result.get("value"),"Unexpected:"+JSON.toPrettyString(result));
	}
	
	@SuppressWarnings("null")
	@Test public void testNewAccount() throws InterruptedException, ExecutionException {
		Convex convex=getNewConvex();
		checkValid(convex);
		Address addr=convex.useNewAccount(1000666);
		assertNotNull(addr);
		Map<String,Object> acc1=convex.queryAccount();
		assertEquals(1000666,((Number)acc1.get("balance")).longValue());
		
		Map<String,Object> result=null;
		for (int i=0; i<10; i++) {
			result=convex.transact("(def a "+i+")");
		}
		assertFalse(result.containsKey("errorCode"),"Error: "+result);
		assertEquals(9L,result.get("value"));
		
		// check we have consumed some balance for transactions
		long finalBalance=convex.queryBalance();
		assertTrue(finalBalance<1000666);
	}
	
	@Test public void testFaucet() {
		Convex convex=getNewConvex();
		checkValid(convex);
		Address addr=convex.useNewAccount();
		Map<String,Object> acc1=convex.queryAccount();
		Map<String,Object> freq=convex.faucet(addr,999);
		assertTrue(freq.containsKey("amount"),"Unexpected: "+freq);
		Map<String,Object> acc2=convex.queryAccount(addr);
		long bal1=((Number)acc1.get("balance")).longValue();
		long bal2=((Number)acc2.get("balance")).longValue();
		
		assertEquals(999,bal2-bal1);
	}
	
	@Test public void testCreateInstance() {
		Convex convex=Convex.connect(TEST_PEER);
		assertNotNull(convex);
	}

	protected void checkValid(Convex convex) {
		if (convex==null) {
			skip=true;
			Assume.assumeTrue(false);
		} else {
			// OK?
		}
	}
}
