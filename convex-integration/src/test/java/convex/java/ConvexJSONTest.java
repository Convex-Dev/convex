package convex.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Symbols;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.init.Init;
import convex.peer.API;
import convex.peer.ConfigException;
import convex.peer.LaunchException;
import convex.peer.Server;
import convex.restapi.RESTServer;

/**
 * Tests for ConvexJSON client functionality
 */
public class ConvexJSONTest {

	static RESTServer server;
	static int port;
	static String host;
	static AKeyPair skp;
	static Address genesis=Init.GENESIS_ADDRESS;
	
	@BeforeAll
	public static void init() throws InterruptedException, ConfigException, LaunchException {
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR, AKeyPair.generate());
		config.put(Keyword.create("faucet"), true);
		Server s=API.launchPeer(config);
		RESTServer rs=RESTServer.create(s);
		rs.start(0);
		port=rs.getPort();
		server=rs;
		skp=s.getKeyPair();
		host="http://localhost:"+port;
	}
	
	@AfterAll 
	public static void cleanShutdown() {
		server.close();
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
		
		// Account should not have requested balance
		assertEquals(AMT,c.queryBalance());
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
		
		// Query *key*
		res=c.query(Symbols.STAR_KEY.toString());
		assertNotNull(res, "Query result should not be null");
		assertEquals(kp.getAccountKey().toString(),res.get("result"));
	}
	
	@Test 
	public void testAccountGenesisInfo() {
		ConvexJSON c=ConvexJSON.connect(host);
		Address addr=genesis;
		c.setAddress(addr);
		
		// Test values for basic new account
		Map<String,Object> res=c.queryAccount();
		assertEquals(addr.longValue(),res.get("address"));
		assertTrue(res.get("balance") instanceof Long);
		assertEquals(skp.getAccountKey(),AccountKey.parse(res.get("key")));
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
		assertEquals("user",res.get("type"));
		
		// Should get null for non-existent account
		res=c.queryAccount(10000);
		assertNull(res);
	}
	
	@Test 
	public void testTransactGenesis() {
		ConvexJSON c=ConvexJSON.connect(host);
		AKeyPair kp=skp;
		Address addr=genesis;
		c.setKeyPair(kp);
		c.setAddress(addr);
		
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
}
