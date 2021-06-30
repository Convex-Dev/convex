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
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadSignatureException;
import convex.core.init.Init;
import convex.core.init.InitConfigTest;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.lang.Symbols;
import convex.core.lang.TestState;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
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

	public static final Server SERVER;

	static {
		// Use fresh State
		State s=Init.createState(InitConfigTest.create());

		Map<Keyword, Object> config = new HashMap<>();
		config.put(Keywords.PORT, 0); // create new port
		config.put(Keywords.STATE, s);
		config.put(Keywords.STORE, EtchStore.createTemp("server-test-store"));
		config.put(Keywords.KEYPAIR, InitConfigTest.FIRST_PEER_KEYPAIR); // use first peer keypair

		SERVER = API.launchPeer(config);
		// wait for server to be launched
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			// continue
		}
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
	public void testServerConnect() throws IOException, InterruptedException, TimeoutException {
		InetSocketAddress hostAddress=SERVER.getHostAddress();

		// Connect to Peer Server using the current store for the client
		Connection pc = Connection.connect(hostAddress, handler, Stores.current());
		AVector<CVMLong> v = Vectors.of(1l, 2l, 3l);
		long id1 = pc.sendQuery(v,InitConfigTest.HERO_ADDRESS);
		Utils.timeout(5000, () -> results.get(id1) != null);
		assertEquals(v, results.get(id1));
	}

// Commented out because it's slow....
//	@Test
//	public void testServerFlood() throws IOException, InterruptedException {
//		InetSocketAddress hostAddress=server.getHostAddress();
//		// This is a test of flooding a client connection with async messages. Should eventually throw an IOExcepion
//		// from backpressure and *not* bring down the server.
//		Convex convex=Convex.connect(hostAddress, VILLAIN_ADDRESS,Init.VILLAIN_KEYPAIR);
//
//		Object cmd=Reader.read("(def tmp (inc tmp))");
//		assertThrows(IOException.class, ()-> {
//			for (int i=0; i<1000000; i++) {
//				convex.transact(Invoke.create(VILLAIN_ADDRESS, 0, cmd));
//			}
//		});
//	}

	@Test public void testBalanceQuery() throws IOException, TimeoutException {
		Convex convex=Convex.connect(SERVER.getHostAddress(),InitConfigTest.VILLAIN_ADDRESS,InitConfigTest.VILLAIN_KEYPAIR);

		// test the connection is still working
		assertNotNull(convex.getBalance(InitConfigTest.VILLAIN_ADDRESS));
	}

	@Test
	public void testConvexAPI() throws IOException, InterruptedException, ExecutionException, TimeoutException {
		Convex convex=Convex.connect(SERVER.getHostAddress(),InitConfigTest.VILLAIN_ADDRESS,InitConfigTest.VILLAIN_KEYPAIR);

		Future<convex.core.Result> f=convex.query(Symbols.STAR_BALANCE);
		convex.core.Result f2=convex.querySync(Symbols.STAR_ADDRESS);

		assertEquals(InitConfigTest.VILLAIN_ADDRESS,f2.getValue());
		assertCVMEquals(TestState.STATE.getBalance(InitConfigTest.VILLAIN_ADDRESS),f.get().getValue());
	}

	@Test
	public void testMissingData() throws IOException, InterruptedException, TimeoutException {

		InetSocketAddress hostAddress=SERVER.getHostAddress();

		// Connect to Peer Server using the current store for the client
		AStore store=Stores.current();
		Connection pc = Connection.connect(hostAddress, handler, store);
		State s=SERVER.getPeer().getConsensusState();
		Hash h=s.getHash();

		boolean sent=pc.sendMissingData(h);
		assertTrue(sent);

		Thread.sleep(200);
		Ref<State> ref=Ref.forHash(h);
		assertNotNull(ref);
	}

	@Test
	public void testAcquireBelief() throws IOException, InterruptedException, ExecutionException, TimeoutException, BadSignatureException {
		synchronized(ServerTest.SERVER) {
			// Stores.setCurrent(Stores.getGlobalStore()); // not needed?
			InetSocketAddress hostAddress=SERVER.getHostAddress();

			// Connect to Peer Server using the current store for the client
			// SignedData<Belief> s=server.getPeer().getSignedBelief();
			// Hash h=s.getHash();
			//System.out.println("SignedBelief Hash="+h);
			//System.out.println("testAcquireBelief store="+Stores.current());

			Convex convex=Convex.connect(hostAddress, InitConfigTest.HERO_ADDRESS, InitConfigTest.HERO_KEYPAIR);

			Future<Result> statusFuture=convex.requestStatus();
			Result status=statusFuture.get(10000,TimeUnit.MILLISECONDS);
			assertFalse(status.isError());
			AVector<?> v=status.getValue();
			Hash h=RT.ensureHash(v.get(0));

			Future<SignedData<Belief>> acquiror=convex.acquire(h);
			SignedData<Belief> ab=acquiror.get(10000,TimeUnit.MILLISECONDS);
			assertTrue(ab.getValue() instanceof Belief);
			assertEquals(h,ab.getHash());
		}
	}

	public long checkSent(Connection pc,SignedData<ATransaction> st) throws IOException {
		long x=pc.sendTransaction(st);
		assertTrue(x>=0);
		return x;
	}

	@Test
	public void testServerTransactions() throws IOException, InterruptedException, TimeoutException {
		synchronized(ServerTest.SERVER) {
			InetSocketAddress hostAddress=SERVER.getHostAddress();

			// Connect to Peer Server using the current store for the client
			Connection pc = Connection.connect(hostAddress, handler, Stores.current());
			long s=SERVER.getPeer().getConsensusState().getAccount(InitConfigTest.HERO_ADDRESS).getSequence();
			Address addr=InitConfigTest.HERO_ADDRESS;
			AKeyPair kp=InitConfigTest.HERO_KEYPAIR;
			long id1 = checkSent(pc,kp.signData(Invoke.create(addr, s+1, Reader.read("[1 2 3]"))));
			long id2 = checkSent(pc,kp.signData(Invoke.create(addr, s+2, Reader.read("(return 2)"))));
			long id2a = checkSent(pc,kp.signData(Invoke.create(addr, s+2, Reader.read("22"))));
			long id3 = checkSent(pc,kp.signData(Invoke.create(addr, s+3, Reader.read("(do (def foo :bar) (rollback 3))"))));
			long id4 = checkSent(pc,kp.signData(Transfer.create(addr, s+4, InitConfigTest.HERO_ADDRESS, 1000)));
			long id5 = checkSent(pc,kp.signData(Call.create(addr, s+5, Init.REGISTRY_ADDRESS, Symbols.FOO, Vectors.of(Maps.empty()))));
			long id6bad = checkSent(pc,kp.signData(Invoke.create(InitConfigTest.VILLAIN_ADDRESS, s+6, Reader.read("(def a 1)"))));
			long id6 = checkSent(pc,kp.signData(Invoke.create(addr, s+6, Reader.read("foo"))));

			long last=id6;

			assertTrue(last>=0);
			assertTrue(!pc.isClosed());

			// wait for results to come back
			assertFalse(Utils.timeout(20000, () -> results.containsKey(last)));
			Thread.sleep(100); // bit more time in case something out of order?

			AVector<CVMLong> v = Vectors.of(1l, 2l, 3l);
			assertCVMEquals(v, results.get(id1));
			assertCVMEquals(2L, results.get(id2));
			assertEquals(ErrorCodes.SEQUENCE, results.get(id2a));
			assertCVMEquals(3L, results.get(id3));
			assertCVMEquals(1000L, results.get(id4));
			assertTrue( results.containsKey(id5));
			assertEquals(ErrorCodes.SIGNATURE, results.get(id6bad));
			assertEquals(ErrorCodes.UNDECLARED, results.get(id6));
		}
	}




}
