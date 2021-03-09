package convex.peer;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.Belief;
import convex.core.ErrorCodes;
import convex.core.Init;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Hash;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadSignatureException;
import convex.core.lang.Reader;
import convex.core.lang.Symbols;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.Call;
import convex.core.transactions.Invoke;
import convex.core.transactions.Transfer;
import convex.core.util.Utils;
import convex.net.Connection;
import convex.net.Message;
import convex.net.ResultConsumer;
import etch.EtchStore;

/**
 * Tests for a fresh standalone server instance
 */
public class ServerTest {

	public static final Server server;
	static final AKeyPair keyPair;
	
	static {
		keyPair = Init.KEYPAIRS[0];

		Map<Keyword, Object> config = new HashMap<>();
		config.put(Keywords.PORT, 0); // create new port
		config.put(Keywords.STATE, Init.STATE);
		config.put(Keywords.STORE, EtchStore.createTemp("server-test-store"));
		config.put(Keywords.KEYPAIR, Init.KEYPAIRS[0]); // use first peer keypair

		server = API.launchPeer(config);
	}
	
	private static final Logger log = Logger.getLogger(ServerTest.class.getName());

	private HashMap<Long, Object> results = new HashMap<>();

	private Consumer<Message> handler = new ResultConsumer() {
		@Override
		protected synchronized void handleResult(long id, Object value) {
			log.finer(id+ " : "+Utils.toString(value));
			results.put(id, value);
		}
		
		@Override
		protected synchronized void handleError(long id, Object code, Object message) {
			log.finer(id+ " ERR: "+Utils.toString(code));
			results.put(id, code);
		}
	};

	@Test
	public void testServerConnect() throws IOException, InterruptedException {
		InetSocketAddress hostAddress=server.getHostAddress();
		
		// Connect to Peer Server using the current store for the client
		Connection pc = Connection.connect(hostAddress, handler, Stores.current());
		AVector<CVMLong> v = Vectors.of(1l, 2l, 3l);
		long id1 = pc.sendQuery(v,Init.HERO);
		Utils.timeout(5000, () -> results.get(id1) != null);
		assertEquals(v, results.get(id1));
	}
	
// Commented out because it's slow....	
//	@Test
//	public void testServerFlood() throws IOException, InterruptedException {
//		InetSocketAddress hostAddress=server.getHostAddress();
//		// This is a test of flooding a client connection with async messages. Should eventually throw an IOExcepion
//		// from backpressure and *not* bring down the server.
//		Convex convex=Convex.connect(hostAddress, Init.VILLAIN,Init.VILLAIN_KP);
//		
//		Object cmd=Reader.read("(def tmp (inc tmp))");
//		assertThrows(IOException.class, ()-> {
//			for (int i=0; i<1000000; i++) {
//				convex.transact(Invoke.create(Init.VILLAIN, 0, cmd));
//			}
//		});
//	}
	
	@Test public void testBadMessage() throws IOException {
		Convex convex=Convex.connect(server.getHostAddress(),Init.VILLAIN,Init.VILLAIN_KP);
		
		// test the connection is still working
		assertNotNull(convex.getBalance(Init.VILLAIN));
	}
	
	@Test
	public void testConvexAPI() throws IOException, InterruptedException, ExecutionException, TimeoutException {
		Convex convex=Convex.connect(server.getHostAddress(),Init.VILLAIN,Init.VILLAIN_KP);
		
		Future<convex.core.Result> f=convex.query(Symbols.STAR_BALANCE);
		convex.core.Result f2=convex.querySync(Symbols.STAR_ADDRESS);
		
		assertEquals(Init.VILLAIN,f2.getValue());
		assertCVMEquals(Init.STATE.getBalance(Init.VILLAIN),f.get().getValue());
	}
	
	@Test
	public void testMissingData() throws IOException, InterruptedException {
		
		InetSocketAddress hostAddress=server.getHostAddress();
		
		// Connect to Peer Server using the current store for the client
		AStore store=Stores.current();
		Connection pc = Connection.connect(hostAddress, handler, store);
		State s=server.getPeer().getConsensusState();
		Hash h=s.getHash();
		
		boolean sent=pc.sendMissingData(h);
		assertTrue(sent);
		
		Thread.sleep(200);
		Ref<State> ref=Ref.forHash(h);
		assertNotNull(ref);
	}
	
	@Test
	public void testAcquireBelief() throws IOException, InterruptedException, ExecutionException, TimeoutException, BadSignatureException {
		// Stores.setCurrent(Stores.getGlobalStore()); // not needed?
		InetSocketAddress hostAddress=server.getHostAddress();
		
		// Connect to Peer Server using the current store for the client
		// SignedData<Belief> s=server.getPeer().getSignedBelief();
		// Hash h=s.getHash();
		//System.out.println("SignedBelief Hash="+h);
		//System.out.println("testAcquireBelief store="+Stores.current());
		
		Convex convex=Convex.connect(hostAddress, Init.HERO, Init.HERO_KP);
		
		Future<Result> statusFuture=convex.requestStatus();
		Result status=statusFuture.get(10000,TimeUnit.MILLISECONDS);
		assertFalse(status.isError());
		AVector<?> v=status.getValue();
		Hash h=(Hash)v.get(0);
		
		Future<SignedData<Belief>> acquiror=convex.acquire(h);
		SignedData<Belief> ab=acquiror.get(10000,TimeUnit.MILLISECONDS);
		assertTrue(ab.getValue() instanceof Belief);
		assertEquals(h,ab.getHash());
	}
	
	@Test
	public void testServerTransactions() throws IOException, InterruptedException {
		InetSocketAddress hostAddress=server.getHostAddress();
		
		// Connect to Peer Server using the current store for the client
		Connection pc = Connection.connect(hostAddress, handler, Stores.current());
		Address addr=Init.FIRST_PEER;
		long id1 = pc.sendTransaction(keyPair.signData(Invoke.create(addr, 1, Reader.read("[1 2 3]"))));
		long id2 = pc.sendTransaction(keyPair.signData(Invoke.create(addr, 2, Reader.read("(return 2)"))));
		long id2a = pc.sendTransaction(keyPair.signData(Invoke.create(addr, 2, Reader.read("22"))));
		long id3 = pc.sendTransaction(keyPair.signData(Invoke.create(addr, 3, Reader.read("(rollback 3)"))));
		long id4 = pc.sendTransaction(keyPair.signData(Transfer.create(addr, 4, Init.HERO, 1000)));
		long id5 = pc.sendTransaction(keyPair.signData(Call.create(addr, 5, Init.REGISTRY_ADDRESS, Symbols.FOO, Vectors.of(Maps.empty()))));
		
		assertTrue(id5>=0);
		assertTrue(!pc.isClosed());
		
		// wait for results to come back
		assertFalse(Utils.timeout(10000, () -> results.containsKey(id5)));
		
		AVector<CVMLong> v = Vectors.of(1l, 2l, 3l);
		assertCVMEquals(v, results.get(id1));
		assertCVMEquals(2L, results.get(id2));
		assertEquals(ErrorCodes.SEQUENCE, results.get(id2a));
		assertCVMEquals(3L, results.get(id3));
		assertCVMEquals(1000L, results.get(id4));
		assertTrue( results.containsKey(id5));
	}
	



}
