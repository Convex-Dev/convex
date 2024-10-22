package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Refs;
import convex.core.data.SignedData;
import convex.core.data.Symbols;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.ResultException;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.net.Connection;

/**
 * Tests for a fresh standalone server cluster instance
 */
public class ServerTest {

	private static TestNetwork network;
	
	@BeforeAll
	public static void init() {
		network = TestNetwork.getInstance();
	}
	
	/**
	 * Smoke test for ConvexLocal connection 
	 * @throws Exception in case of error
	 */
	@Test
	public void testLocalConnect() throws Exception {
		Server server=network.SERVER;

		AKeyPair  kp=server.getKeyPair();

		Convex convex = network.CONVEX;
		assertTrue(convex.getBalance()>0);
		
		Result r=convex.transactSync("(create-account "+kp.getAccountKey()+")");
		Address user=r.getValue();
		assertNotNull(user);
		
		r=convex.transactSync("(transfer "+user+" 10000000)");
		assertFalse(r.isError());
		
		convex=Convex.connect(server, user, kp);
		assertEquals(10000000,convex.getBalance());

		r=convex.transactSync("(do (transfer "+user+" 100000) *balance*)");
		assertEquals("10000000",r.getValue().toString());

	}

	@Test
	public void testServerFlood() throws IOException, InterruptedException, TimeoutException {
		InetSocketAddress hostAddress=network.SERVER.getHostAddress();
		// This is a test of flooding a client connection with async messages. Should eventually throw an IOExcepion
		// from backpressure and *not* bring down the server.
		ConvexRemote convex=Convex.connect(hostAddress, network.VILLAIN,network.VILLAIN_KEYPAIR);

		ACell cmd=Reader.read("(def tmp (inc tmp))");
		// Might block, but no issue
		for (int i=0; i<100; i++) {
			convex.transact(Invoke.create(network.VILLAIN, 0, cmd));
		}
		
		// Should still get status OK
		Convex convex2=Convex.connect(hostAddress, network.HERO,network.HERO_KEYPAIR);
		assertNotNull(convex2.requestStatusSync(2000));
	}

	@Test
	public void testBalanceQuery() throws IOException, TimeoutException, ResultException {
		Convex convex=Convex.connect(network.SERVER.getHostAddress(),network.VILLAIN,network.VILLAIN_KEYPAIR);

		// test the connection is still working
		assertNotNull(convex.getBalance(network.VILLAIN));
	}
	
	@Test
	public void testSequence() throws ResultException, TimeoutException, InterruptedException {
		Convex convex=network.getClient();
		// sequence number should be zero for fresh account
		assertEquals(0,convex.getSequence());
		
		// Queries and transactions should return the value as at start of transaction
		assertEquals(0L,(Long)RT.jvm(convex.querySync("*sequence*").getValue()));
		assertEquals(0L,(Long)RT.jvm(convex.transactSync("*sequence*").getValue()));
		
		// Sequence number should be incremented after previous transaction
		assertEquals(1,convex.getSequence());
	}

	@Test
	public void testConvexAPI() throws IOException, InterruptedException, ExecutionException, TimeoutException {
		synchronized(network.SERVER) {
			Convex convex=network.getClient();
	
			Future<convex.core.Result> f=convex.query(Symbols.STAR_BALANCE);
			convex.core.Result f2=convex.querySync(Symbols.STAR_ADDRESS);
	
			assertEquals(convex.getAddress(),f2.getValue());
			assertTrue(f.get().getValue() instanceof CVMLong);
			
			// Note difference by argument type. `nil` code can make a valid transaction
			assertThrows(IllegalArgumentException.class,()->convex.transact((ATransaction)null));
			{
				Result r=convex.transactSync((ACell)null);
				// System.out.println(r);
				assertEquals(null,r.getValue());
			}
			
			convex.core.Result r3=convex.querySync(Reader.read("(fail :foo)"));
			assertTrue(r3.isError());
			assertEquals(ErrorCodes.ASSERT,r3.getErrorCode());
			assertEquals(Keywords.FOO,r3.getValue());
			assertNotNull(r3.getTrace());
		}
	}

	@Test
	public void testAcquireMissing() throws IOException, InterruptedException, ExecutionException, TimeoutException, BadSignatureException {
		Hash BAD_HASH=Hash.fromHex("BAD0BAD0BAD0BAD0BAD0BAD0BAD0BAD0BAD0BAD0BAD0BAD0BAD0BAD0BAD0BAD0");
		
		synchronized(network.SERVER) {

			Convex convex=Convex.connect(network.SERVER.getHostAddress());
			assertThrows(ExecutionException.class,()->{
				ACell c = convex.acquire(BAD_HASH).get();
				System.out.println("Didn't expect to acquire: "+c);
			});
		}
	}
	
	@Test
	public void testAcquireBeliefLocal() throws IOException, InterruptedException, ExecutionException, TimeoutException, BadSignatureException {
		synchronized(network.SERVER) {

			Convex convex=network.CONVEX;

			Future<Result> statusFuture=convex.requestStatus();
			Result status=statusFuture.get(10000,TimeUnit.MILLISECONDS);
			assertFalse(status.isError());
			AVector<?> v=status.getValue();
			Hash h=RT.ensureHash(v.get(0));
			
			AStore peerStore=network.SERVER.getStore();
			Ref<?> pr=peerStore.refForHash(h);
			assertTrue(pr.isPersisted()); // should be persisted in local peer store
	
			// TODO this maybe needs fixing!
			// Refs.checkConsistentStores(pr, peerStore);
		
			Future<Belief> acquiror=convex.acquire(h);
			Belief ab=acquiror.get(10000,TimeUnit.MILLISECONDS);
			assertTrue(ab instanceof Belief);
			assertEquals(h,ab.getHash());
		}
	}
	
	@Test
	public void testAcquireBeliefRemote() throws IOException, InterruptedException, ExecutionException, TimeoutException, BadSignatureException {
		synchronized(network.SERVER) {

			Convex convex=Convex.connect(network.SERVER.getHostAddress());

			Future<Result> statusFuture=convex.requestStatus();
			Result status=statusFuture.get(10000,TimeUnit.MILLISECONDS);
			assertFalse(status.isError());
			AVector<?> v=status.getValue();
			Hash h=RT.ensureHash(v.get(0));

			Future<Belief> acquiror=convex.acquire(h);
			Belief ab=acquiror.get(10000,TimeUnit.MILLISECONDS);
			Refs.checkConsistentStores(ab.getRef(),Stores.current());
			assertTrue(ab instanceof Belief);
			assertEquals(h,ab.getHash());
		}
	}
	
	@Test
	public void testQueryStrings() throws TimeoutException, IOException, InterruptedException {
		Convex convex=network.CONVEX;
		assertEquals(convex.getAddress(),convex.querySync("*address*").getValue());
		assertEquals(CVMLong.ONE,convex.querySync("3 2 1").getValue());
		
		// Can query for initial foundation account, it has no environment
		assertEquals(Maps.empty(),convex.querySync(Symbols.STAR_ENV,Init.RESERVE_ADDRESS).getValue());
		// Thread.sleep(1000000000);
	}

	@Test
	public void testAcquireState() throws IOException, InterruptedException, ExecutionException, TimeoutException, BadSignatureException {
		synchronized(network.SERVER) {

			Convex convex=network.CONVEX;

			State s=convex.acquireState().get(80000,TimeUnit.MILLISECONDS);
			assertTrue(s instanceof State);
		}
	}

	public long checkSent(Connection pc,SignedData<ATransaction> st) throws IOException {
		long x=pc.sendTransaction(st);
		assertTrue(x>=0);
		return x;
	}

}
