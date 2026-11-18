package convex.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Symbols;
import convex.core.data.AccountKey;
import convex.core.init.Init;
import convex.test.LocalClientNetwork;

/**
 * Tests for ConvexJSON client functionality
 */
@ExtendWith(LocalClientNetwork.Extension.class)
@Timeout(30)
public class ConvexJSONTest {
	private String host;
	private AccountKey peerKey;

	@BeforeEach
	void useNetwork(LocalClientNetwork network) {
		host=network.uri().toString();
		peerKey=network.peerKey();
	}

	@Test 
	public void testCreateAccount() {
		ConvexJSON c=ConvexJSON.connect(host);
		AKeyPair kp=AKeyPair.generate();
		Address addr=c.createAccount(kp);
		assertNotNull(addr);
		
		// second account should be different
		assertNotEquals(addr,c.createAccount(kp));
		assertEquals(0,c.queryBalance(addr));
	}
	
	@Test 
	public void testFaucet() {
		ConvexJSON c=ConvexJSON.connect(host);
		Address addr=c.useNewAccount();
		assertNotNull(addr);
		
		// New account should have zero balance
		assertEquals(0L,c.queryBalance());
		
		long AMT=1000000;
		Map<String,Object> resp=c.faucet(addr, AMT);
		
		// Response should contain amount allocated
		assertEquals(AMT,resp.get("amount"));
		
		// Account should now have the requested balance
		assertEquals(AMT,c.queryBalance());
		Map<String,Object> topUp=c.faucet(addr,999);
		assertEquals(999L,topUp.get("amount"));
		assertEquals(AMT+999,c.queryBalance());
	}
	
	@Test 
	public void testAccount() {
		ConvexJSON c=ConvexJSON.connect(host);
		AKeyPair kp=AKeyPair.generate();
		Address addr=c.createAccount(kp);
		c.setKeyPair(kp);
		c.setAddress(addr);
		c.faucet(addr, 1000000);
		
		// Query *address*
		Map<String,Object> res=c.query("*address*");
		assertNotNull(res, "Query result should not be null");
		assertEquals(addr.toString(),res.get("result"));
		assertEquals(addr,Address.parse(res.get("value")));
		
		// Query *key*
		res=c.query(Symbols.STAR_KEY.toString());
		assertNotNull(res, "Query result should not be null");
		assertEquals(kp.getAccountKey().toString(),res.get("result"));
	}
	
	@Test 
	public void testAccountGenesisInfo() {
		ConvexJSON c=ConvexJSON.connect(host);
		Address addr=Init.GENESIS_ADDRESS;
		c.setAddress(addr);
		
		// Test values for basic new account
		Map<String,Object> res=c.queryAccount();
		assertEquals(addr.longValue(),res.get("address"));
		assertTrue(res.get("balance") instanceof Long);
		assertEquals(peerKey,AccountKey.parse(res.get("key")));
	}
	
	@Test 
	public void testQueryAccount() {
		ConvexJSON c=ConvexJSON.connect(host);
		AKeyPair kp=AKeyPair.generate();
		Address addr=c.createAccount(kp);
		c.setKeyPair(kp);
		c.setAddress(addr);
		c.faucet(addr, 1000000);
		
		// Test values for basic new account
		Map<String,Object> res=c.queryAccount();
		assertEquals(addr.longValue(),res.get("address"));
		assertEquals(1000000L,res.get("balance"));
		assertEquals(0L,res.get("sequence"));
		assertTrue(res.containsKey("memorySize"));
		assertEquals("user",res.get("type"));
		
		// Should get null for non-existent account
		res=c.queryAccount(10000);
		assertNull(res);
	}
	
	@Test 
	public void testTransact() {
		ConvexJSON c=ConvexJSON.connect(host);
		c.useNewAccount(1_000_000);

		Map<String,Object> rm=c.transact("(+ 1 2)");
		assertNotNull(rm, "Transaction result should not be null");
		assertEquals(3L,rm.get("value"));
	}
	
	
	@Test 
	public void testTransactNoFunds() {
		ConvexJSON c=ConvexJSON.connect(host);
		AKeyPair kp=AKeyPair.generate();
		Address addr=c.createAccount(kp);
		c.setKeyPair(kp);
		c.setAddress(addr);
		
		Map<String,Object> rm=c.transact("(+ 1 2)");
		assertNotNull(rm, "Transaction result should not be null");
		assertEquals("JUICE",rm.get("errorCode"));
		
		c.faucet(addr, 1000000);
		Long bal=c.queryBalance(addr);
		assertTrue(bal>0);
		
		rm=c.transact("(+ 1 2)");
		assertNotNull(rm, "Transaction result should not be null");
		assertEquals(3L,rm.get("value"));
		assertEquals("3",rm.get("result"));
		
		assertTrue(c.queryBalance()<bal);
	}

	@Test
	public void testQueryAsync() throws Exception {
		ConvexJSON c=ConvexJSON.connect(host);
		c.useNewAccount();
		Map<String,Object> result=c.queryAsync("(+ 1 2)").get(10,TimeUnit.SECONDS);
		assertFalse(result.containsKey("errorCode"),result::toString);
		assertEquals(3L,result.get("value"));
		Map<String,Object> account=c.queryAccountAsync(c.getAddress()).get(10,TimeUnit.SECONDS);
		assertEquals(c.getAddress().longValue(),account.get("address"));
	}

	@Test
	public void testNewAccountAndRepeatedTransactions() throws Exception {
		ConvexJSON c=ConvexJSON.connect(host);
		Address address=c.useNewAccount(1_000_666);
		assertNotNull(address);
		assertEquals(1_000_666L,c.queryBalance());
		// Three transactions: each after the first needs the sequence the one before advanced
		for (int i=0;i<3;i++) {
			Map<String,Object> result=c.transactAsync("(def a "+i+")").get(10,TimeUnit.SECONDS);
			assertFalse(result.containsKey("errorCode"),result::toString);
			assertEquals((long)i,result.get("value"));
		}
		assertEquals(2L,c.query("a").get("value"));
		assertEquals(3L,c.queryAccount().get("sequence"));
		assertTrue(c.queryBalance()<1_000_666L);
	}
}
