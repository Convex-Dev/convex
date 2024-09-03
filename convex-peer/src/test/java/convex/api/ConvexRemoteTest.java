package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519Signature;
import convex.core.data.Address;
import convex.core.data.Blobs;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ResultException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.lang.ops.Constant;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.net.Connection;
import convex.net.Message;
import convex.net.MessageType;
import convex.peer.TestNetwork;

/**
 * Tests for a Convex Client connection
 */
public class ConvexRemoteTest {

	static Address ADDRESS;
	static final AKeyPair KEYPAIR = AKeyPair.generate();

	private static TestNetwork network;

	@BeforeAll
	public static void init() throws InterruptedException, ResultException, ExecutionException, TimeoutException {
		network =  TestNetwork.getInstance();
		synchronized(network.SERVER) {
			ADDRESS=network.CONVEX.createAccountSync(KEYPAIR.getAccountKey());
			Result r=network.CONVEX.transfer(ADDRESS, 1000000000L).get(5000,TimeUnit.MILLISECONDS);
			assertFalse(r.isError(),()->"Error transferring init funds: "+r);
		}
	}

	@Test
	public void testConnection() throws IOException, TimeoutException {
		synchronized (network.SERVER) {
			ConvexRemote convex = Convex.connect(network.SERVER.getHostAddress());
			assertTrue(convex.isConnected());
			convex.close();
			assertFalse(convex.isConnected());
			
			convex.reconnect();
			assertTrue(convex.isConnected());
		}
	}
	
	@Test
	public void testBadQueryMessage() throws IOException, TimeoutException {
		ConvexRemote convex = Convex.connect(network.SERVER.getHostAddress());
		Connection conn=convex.connection;
		conn.sendMessage(Message.create(MessageType.QUERY, Blobs.empty()));
	}

	@Test
	public void testBadConnect() throws IOException, TimeoutException {
		assertThrows(IOException.class,()->Convex.connect(new InetSocketAddress("localhost", 0)));
	}
	
	@Test
	public void testConvex() throws IOException, TimeoutException, InterruptedException {
		synchronized (network.SERVER) {
			Convex convex = Convex.connect(network.SERVER.getHostAddress(), ADDRESS, KEYPAIR);
			Result r = convex.transactSync(Invoke.create(ADDRESS, 0, Reader.read("*address*")), 5000);
			assertNull(r.getErrorCode(), "Error:" + r.toString());
			assertEquals(ADDRESS, r.getValue());
		}
	}

	@Test
	public void testBadSignature() throws IOException, TimeoutException, InterruptedException, ExecutionException, ResultException {
		synchronized (network.SERVER) {
			Convex convex = Convex.connect(network.SERVER.getHostAddress(), ADDRESS, KEYPAIR);
			Ref<ATransaction> tr = Invoke.create(ADDRESS, convex.getSequence()+1, Reader.read("*address*")).getRef();
			Result r = convex.transact(SignedData.create(KEYPAIR.getAccountKey(), Ed25519Signature.ZERO, tr)).get();
			assertEquals(ErrorCodes.SIGNATURE, r.getErrorCode());
		}
	}
	
	/**
	 * Test for sending a "transaction" that is actually not a transaction, i.e. clearly the wrong format
	 */
	@Test
	public void testBadTransaction() throws IOException, TimeoutException, InterruptedException, ExecutionException, ResultException {
		synchronized (network.SERVER) {
			Convex convex = Convex.connect(network.SERVER.getHostAddress(), ADDRESS, KEYPAIR);
			@SuppressWarnings({ "unchecked", "rawtypes" })
			SignedData<ATransaction> tr = (SignedData)KEYPAIR.signData(CVMLong.ONE); // clearly not an ATransaction...
			Result r = convex.transact(tr).get();
			assertEquals(ErrorCodes.FORMAT, r.getErrorCode());
			assertEquals(SourceCodes.PEER, r.getSource());
		}
	}
	
	/**
	 * Test for sending a "transaction" for an account that does not exist. Peer should catch this!
	 */
	@Test
	public void testNobody() throws IOException, TimeoutException, InterruptedException, ExecutionException  {
		synchronized (network.SERVER) {
			Convex convex = Convex.connect(network.SERVER.getHostAddress(), Address.create(666666), KEYPAIR);
			Result r = convex.transact(CVMLong.ONE).get();
			assertEquals(ErrorCodes.NOBODY, r.getErrorCode());
			assertEquals(SourceCodes.PEER, r.getSource());
		}
	}
	
	@Test
	public void testBadSequence() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		synchronized (network.SERVER) {
			Convex convex = network.getClient();
			ATransaction tr = Invoke.create(convex.getAddress(), 10, Reader.read("*address*"));
			Result r = convex.transactSync(tr);
			assertEquals(ErrorCodes.SEQUENCE, r.getErrorCode());
			assertEquals(SourceCodes.CVM, r.getSource()); // currently gets as far as :CVM. OK, but cost to peer?
			
			// Sequence should recover
			r=convex.transactSync("(+ 2 3)");
			assertEquals(CVMLong.create(5),r.getValue());
		}
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testManyTransactions() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		synchronized (network.SERVER) {
			Convex convex = Convex.connect(network.SERVER.getHostAddress(), ADDRESS, KEYPAIR);
			int n = 100;
			Future<Result>[] rs = new Future[n];
			for (int i = 0; i < n; i++) {
				Future<Result> f = convex.transact(Invoke.create(ADDRESS, 0, Constant.of(i)));
				rs[i] = f;
			}
			for (int i = 0; i < n; i++) {
				Result r = rs[i].get(6000, TimeUnit.MILLISECONDS);
				assertNull(r.getErrorCode(), ()->"Error:" + r.toString());
			}
		}
	}
	
	@Test
	public void testReceivedCount() throws IOException, TimeoutException, InterruptedException, ResultException {
		synchronized (network.SERVER) {
			ConvexRemote convex = Convex.connect(network.SERVER.getHostAddress(), ADDRESS, KEYPAIR);
			Connection conn=convex.connection;

			long seq=convex.getSequence();
			assertEquals(1,conn.getReceivedCount());
			
			// conn.setReceiveHook(m-> System.out.println(m));
			
			convex.querySync("'foo");
			assertEquals(2,conn.getReceivedCount());
			
			Result r=convex.transactSync("*sequence*");
			assertNull(r.getErrorCode());
			assertEquals(seq,RT.ensureLong(r.getValue()).longValue());
			assertEquals(3,conn.getReceivedCount());
		}
	}

}
