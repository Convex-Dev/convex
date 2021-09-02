package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Belief;
import convex.core.Coin;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
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
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.lang.Symbols;
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
 * Tests for a fresh standalone server cluster instance
 */
public class ServerTest {

	private static final Logger log = LoggerFactory.getLogger(ServerTest.class.getName());

	private HashMap<Long, Object> results = new HashMap<>();

	private static TestNetwork network;

	private Consumer<Message> handler = new ResultConsumer() {
		@Override
		protected synchronized void handleNormalResult(long id, ACell value) {
			String msg=id+ " : "+Utils.toString(value);
			//System.err.println(msg);
			log.debug(msg);
			results.put(id, value);
		}

		@Override
		protected synchronized void handleError(long id, Object code, Object message) {
			String msg=id+ " ERR: "+Utils.toString(code)+ " : "+message;
			//System.err.println(msg);
			log.debug(msg);

			results.put(id, code);
		}
	};

	@BeforeAll
	public static void init() {
		network =  TestNetwork.getInstance();
	}

	@Test
	public void testServerConnect() throws IOException, InterruptedException, TimeoutException {
		InetSocketAddress hostAddress=network.SERVER.getHostAddress();

		// Connect to Peer Server using the current store for the client
		Connection pc = Connection.connect(hostAddress, handler, Stores.current());
		AVector<CVMLong> v = Vectors.of(1l, 2l, 3l);
		long id1 = pc.sendQuery(v,network.HERO);
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

	@Test
	public void testBalanceQuery() throws IOException, TimeoutException {
		Convex convex=Convex.connect(network.SERVER.getHostAddress(),network.VILLAIN,network.VILLAIN_KEYPAIR);

		// test the connection is still working
		assertNotNull(convex.getBalance(network.VILLAIN));
	}

	@Test
	public void testConvexAPI() throws IOException, InterruptedException, ExecutionException, TimeoutException {
		Convex convex=Convex.connect(network.SERVER.getHostAddress(),network.VILLAIN,network.VILLAIN_KEYPAIR);

		Future<convex.core.Result> f=convex.query(Symbols.STAR_BALANCE);
		convex.core.Result f2=convex.querySync(Symbols.STAR_ADDRESS);

		assertEquals(network.VILLAIN,f2.getValue());
		assertTrue(f.get().getValue() instanceof CVMLong);
		
		
		convex.core.Result r3=convex.querySync(Reader.read("(fail :foo)"));
		assertTrue(r3.isError());
		assertEquals(ErrorCodes.ASSERT,r3.getErrorCode());
		assertEquals(Keywords.FOO,r3.getValue());
		assertNotNull(r3.getTrace());
	}

	@Test
	public void testMissingData() throws IOException, InterruptedException, TimeoutException {

		InetSocketAddress hostAddress=network.SERVER.getHostAddress();

		// Connect to Peer Server using the current store for the client
		AStore store=Stores.current();
		Connection pc = Connection.connect(hostAddress, handler, store);
		State s=network.SERVER.getPeer().getConsensusState();
		Hash h=s.getHash();

		boolean sent=pc.sendMissingData(h);
		assertTrue(sent);

		Thread.sleep(200);
		Ref<State> ref=Ref.forHash(h);
		assertNotNull(ref);
	}

	@Test
	public void testJoinNetwork() throws IOException, InterruptedException, ExecutionException, TimeoutException, BadSignatureException {
		AKeyPair kp=AKeyPair.generate();
		AccountKey peerKey=kp.getAccountKey();

		long STAKE=1000000000;
		synchronized(network.SERVER) {
			Convex heroConvex=network.CONVEX;

			// Create new peer controller account
			Address controller=heroConvex.createAccountSync(kp.getAccountKey());
			Result trans=heroConvex.transferSync(controller,Coin.DIAMOND);
			assertFalse(trans.isError());

			// create test user account
			Address user=heroConvex.createAccountSync(kp.getAccountKey());
			trans=heroConvex.transferSync(user,STAKE);
			assertFalse(trans.isError());

			Convex convex=Convex.connect(network.SERVER.getHostAddress(), controller, kp);
			trans=convex.transactSync(Invoke.create(controller, 0, "(create-peer "+peerKey+" "+STAKE+")"));
			assertEquals(RT.cvm(STAKE),trans.getValue());
			//Thread.sleep(1000); // sleep a bit to allow background stuff

			HashMap<Keyword,Object> config=new HashMap<>();
			config.put(Keywords.KEYPAIR,kp);
			config.put(Keywords.STORE,EtchStore.createTemp());
			config.put(Keywords.SOURCE,network.SERVER.getHostAddress());

			Server newServer=API.launchPeer(config);

			// make peer connections directly
			newServer.getConnectionManager().connectToPeer(network.SERVER.getHostAddress());
			network.SERVER.getConnectionManager().connectToPeer(newServer.getHostAddress());

			// should be in consensus at this point since just synced
			// note: shouldn't matter which is the current store
			assertEquals(newServer.getPeer().getConsensusState(),network.SERVER.getPeer().getConsensusState());

			Convex client=Convex.connect(newServer.getHostAddress(), user, kp);
			assertEquals(user,client.transactSync(Invoke.create(user, 0, "*address*")).getValue());

			Result r=client.requestStatus().get(1000,TimeUnit.MILLISECONDS);
			assertFalse(r.isError());
		}
	}

	@Test
	public void testAcquireBelief() throws IOException, InterruptedException, ExecutionException, TimeoutException, BadSignatureException {
		synchronized(network.SERVER) {

			Convex convex=network.CONVEX;

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

	@Test
	public void testAcquireState() throws IOException, InterruptedException, ExecutionException, TimeoutException, BadSignatureException {
		synchronized(network.SERVER) {

			Convex convex=network.CONVEX;

			State s=convex.acquireState().get(60000,TimeUnit.MILLISECONDS);
			assertTrue(s instanceof State);
		}
	}

	public long checkSent(Connection pc,SignedData<ATransaction> st) throws IOException {
		long x=pc.sendTransaction(st);
		assertTrue(x>=0);
		return x;
	}

	@Test
	public void testServerTransactions() throws IOException, InterruptedException, TimeoutException {
		synchronized(network.SERVER) {
			InetSocketAddress hostAddress=network.SERVER.getHostAddress();

			// Connect to Peer Server using the current store for the client
			Connection pc = Connection.connect(hostAddress, handler, Stores.current());
			Address addr=network.SERVER.getPeerController();
			long s=network.SERVER.getPeer().getConsensusState().getAccount(addr).getSequence();
			AKeyPair kp=network.SERVER.getKeyPair();
			long id1 = checkSent(pc,kp.signData(Invoke.create(addr, s+1, Reader.read("[1 2 3]"))));
			long id2 = checkSent(pc,kp.signData(Invoke.create(addr, s+2, Reader.read("(return 2)"))));
			long id2a = checkSent(pc,kp.signData(Invoke.create(addr, s+2, Reader.read("22"))));
			long id3 = checkSent(pc,kp.signData(Invoke.create(addr, s+3, Reader.read("(do (def foo :bar) (rollback 3))"))));
			long id4 = checkSent(pc,kp.signData(Transfer.create(addr, s+4, addr, 1000)));
			long id5 = checkSent(pc,kp.signData(Call.create(addr, s+5, Init.REGISTRY_ADDRESS, Symbols.FOO, Vectors.of(Maps.empty()))));
			long id6bad = checkSent(pc,kp.signData(Invoke.create(addr.offset(2), s+6, Reader.read("(def a 1)"))));
			long id6 = checkSent(pc,kp.signData(Invoke.create(addr, s+6, Reader.read("foo"))));

			long last=id6;

			assertTrue(last>=0);
			assertTrue(!pc.isClosed());

			// wait for results to come back
			assertFalse(Utils.timeout(10000, () -> results.containsKey(last)));
			Thread.sleep(100); // bit more time in case something out of order?

			AVector<CVMLong> v = Vectors.of(1l, 2l, 3l);
			assertEquals(v, results.get(id1));
			assertEquals(RT.cvm(2L), results.get(id2));
			assertEquals(ErrorCodes.SEQUENCE, results.get(id2a));
			assertEquals(RT.cvm(3L), results.get(id3));
			assertEquals(RT.cvm(1000L), results.get(id4));
			assertTrue( results.containsKey(id5));
			assertEquals(ErrorCodes.SIGNATURE, results.get(id6bad));
			assertEquals(ErrorCodes.UNDECLARED, results.get(id6));
		}
	}

}
