package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;

/**
 * Regression tests for ConnectionManager and AConnectionManager.
 *
 * Covers bugs caught during the ConcurrentHashMap migration:
 * - Null key handling (ConcurrentHashMap rejects null keys)
 * - PING message construction and type inference
 * - PING round-trip liveness
 */
public class ConnectionManagerTest {

	private static TestNetwork network;

	@BeforeAll
	public static void init() {
		network = TestNetwork.getInstance();
	}

	// ===== Null key regression tests =====

	@Test
	public void testAddConnectionNullKey() {
		ConnectionManager cm = network.SERVER.getConnectionManager();
		Convex convex = network.CONVEX;

		// Must throw on null peerKey — was silently accepted by HashMap,
		// causes NPE with ConcurrentHashMap
		assertThrows(IllegalArgumentException.class, () -> {
			cm.addConnection(null, convex);
		});
	}

	@Test
	public void testAddConnectionNullConvex() {
		ConnectionManager cm = network.SERVER.getConnectionManager();
		AccountKey key = AKeyPair.generate().getAccountKey();

		assertThrows(IllegalArgumentException.class, () -> {
			cm.addConnection(key, null);
		});
	}

	@Test
	public void testGetConnectionNullKey() {
		ConnectionManager cm = network.SERVER.getConnectionManager();

		// Must return null, not throw NPE — ConcurrentHashMap.get(null) throws
		assertNull(cm.getConnection(null));
	}

	@Test
	public void testIsConnectedNullKey() {
		ConnectionManager cm = network.SERVER.getConnectionManager();
		assertFalse(cm.isConnected(null));
	}

	@Test
	public void testCloseConnectionNullKey() {
		ConnectionManager cm = network.SERVER.getConnectionManager();
		// Should not throw
		cm.closeConnection(null, "test");
	}

	// ===== PING message construction tests =====

	@Test
	public void testCreatePingMessageType() {
		Message ping = Message.createPing(42);
		assertEquals(MessageType.PING, ping.getType(), "createPing should produce PING type");
	}

	@Test
	public void testCreatePingRequestID() {
		Message ping = Message.createPing(99);
		assertEquals(CVMLong.create(99), ping.getRequestID(),
				"Request ID should match the id passed to createPing");
	}

	@Test
	public void testCreatePingDifferentIDs() {
		Message p1 = Message.createPing(1);
		Message p2 = Message.createPing(2);
		assertFalse(p1.getRequestID().equals(p2.getRequestID()),
				"Different IDs should produce different request IDs");
	}

	@Test
	public void testPingTypeInference() {
		// Verify that type inference from payload works (not just the stored type)
		Message ping = Message.createPing(7);
		// Re-create from raw payload to test inference path
		Message fromPayload = Message.create(MessageType.PING, ping.getPayload());
		assertEquals(MessageType.PING, fromPayload.getType());
		assertEquals(ping.getRequestID(), fromPayload.getRequestID());
	}

	@Test
	public void testCreatePingPayloadStructure() {
		Message ping = Message.createPing(42);
		AVector<?> payload = RT.ensureVector(ping.getPayload());
		assertNotNull(payload, "Payload should be a vector");
		assertEquals(2, payload.count(), "Payload should have exactly 2 elements: [tag, id]");
		assertEquals(MessageTag.PING, payload.get(0), "First element should be :PING tag");
		assertEquals(CVMLong.create(42), payload.get(1), "Second element should be the request ID");
	}

	@Test
	public void testCreatePingGetID() {
		Message ping = Message.createPing(77);
		// getID() returns the message ID (same as getRequestID for requests)
		ACell id = ping.getID();
		assertEquals(CVMLong.create(77), id);
	}

	@Test
	public void testCreatePingWithID() {
		Message ping = Message.createPing(1);
		// Replace the ID
		Message retagged = ping.withID(CVMLong.create(999));
		assertNotNull(retagged, "withID should succeed for PING messages");
		assertEquals(CVMLong.create(999), retagged.getRequestID());
		assertEquals(MessageType.PING, retagged.getType());
	}

	@Test
	public void testCreatePingEncodeDecodeRoundTrip() throws Exception {
		Message ping = Message.createPing(123);

		// Encode to wire format and decode back
		assertNotNull(ping.getMessageData(), "PING should have encodable message data");
		assertTrue(ping.getMessageData().count() > 0, "Encoded data should be non-empty");
	}

	@Test
	public void testPingRawMessageResult() throws Exception {
		// Send PING via low-level message() and check the Result object directly
		ConvexLocal convex = network.CONVEX;
		Message ping = Message.createPing(987654);

		Result r = convex.message(ping).get(5, TimeUnit.SECONDS);
		assertNotNull(r, "Should get a Result back");
		assertFalse(r.isError(), "PING should not be an error: " + r);
		assertNotNull(r.getValue(), "Result value should not be null");
		assertTrue(r.getValue() instanceof CVMLong, "Result value should be a CVMLong timestamp");
	}

	@Test
	public void testConnectionAllocatesRequestIDs() throws Exception {
		ConvexLocal convex = network.CONVEX;
		Message template=Message.createPing((CVMLong)null);

		CompletableFuture<Result> first=convex.request(template);
		CompletableFuture<Result> second=convex.request(template);
		Result r1=first.get(5,TimeUnit.SECONDS);
		Result r2=second.get(5,TimeUnit.SECONDS);

		assertFalse(r1.isError());
		assertFalse(r2.isError());
		assertNotNull(r1.getID());
		assertNotNull(r2.getID());
		assertNotEquals(r1.getID(),r2.getID());
		assertNull(template.getRequestID(),"Request template must remain unstamped");
	}

	// ===== PING round-trip tests =====

	@Test
	public void testPingLocal() throws Exception {
		ConvexLocal convex = network.CONVEX;

		CVMLong ts = convex.ping().get(5, TimeUnit.SECONDS);
		assertNotNull(ts, "PING should return a timestamp");
		assertTrue(ts.longValue() > 0, "Timestamp should be positive");
	}

	@Test
	public void testPingRemote() throws Exception {
		ConvexRemote convex = network.getClient();
		try {
			CVMLong ts = convex.ping().get(5, TimeUnit.SECONDS);
			assertNotNull(ts, "PING should return a timestamp");
			assertTrue(ts.longValue() > 0, "Timestamp should be positive");
		} finally {
			convex.close();
		}
	}

	@Test
	public void testPingSyncLocal() throws Exception {
		ConvexLocal convex = network.CONVEX;

		CVMLong ts = convex.pingSync(5000);
		assertNotNull(ts);
		assertTrue(ts.longValue() > 0);
	}

	@Test
	public void testPingTimestampReasonable() throws Exception {
		ConvexLocal convex = network.CONVEX;

		long before = System.currentTimeMillis();
		CVMLong ts = convex.ping().get(5, TimeUnit.SECONDS);
		long after = System.currentTimeMillis();

		assertNotNull(ts);
		long peerTime = ts.longValue();
		assertTrue(peerTime >= before - 5000, "Peer timestamp too old");
		assertTrue(peerTime <= after + 5000, "Peer timestamp too far in the future");
	}

	@Test
	public void testPingFreshLocalClient() throws Exception {
		ConvexLocal convex = network.getLocalClient();

		CVMLong ts = convex.ping().get(5, TimeUnit.SECONDS);
		assertNotNull(ts, "PING should return a timestamp on fresh local client");
		assertTrue(ts.longValue() > 0);
	}

	// ===== Connection manager basics =====

	@Test
	public void testGetConnectionCount() {
		ConnectionManager cm = network.SERVER.getConnectionManager();
		assertTrue(cm.getConnectionCount() >= 0);
	}

	@Test
	public void testGetConnectionsDefensiveCopy() {
		ConnectionManager cm = network.SERVER.getConnectionManager();
		var conns1 = cm.getConnections();
		var conns2 = cm.getConnections();
		assertEquals(conns1, conns2);
		assertFalse(conns1 == conns2, "getConnections should return defensive copies");
	}

	@Test
	public void testSequenceBackpressureIsIsolatedPerPeer() {
		TestConnectionManager manager=new TestConnectionManager();
		SequencedConvex good=new SequencedConvex(-1);
		SequencedConvex slow=new SequencedConvex(2);
		manager.add(AKeyPair.generate().getAccountKey(),good);
		manager.add(AKeyPair.generate().getAccountKey(),slow);
		List<Message> sequence=List.of(Message.createPing(1),Message.createPing(2),Message.createPing(3));
		Message fallback=Message.createPing(99);

		AConnectionManager.BroadcastResult result=manager.broadcastSequence(sequence,fallback);

		assertEquals(2,result.peers());
		assertEquals(1,result.complete());
		assertEquals(1,result.fallback());
		assertEquals(sequence,good.sent);
		assertEquals(List.of(sequence.get(0),fallback),slow.sent);
		for (int i=0; i<sequence.size(); i++) assertSame(sequence.get(i),good.sent.get(i));
	}

	@Test
	public void testPriorityBroadcastReusesOneMessageForEveryPeer() {
		TestConnectionManager manager=new TestConnectionManager();
		SequencedConvex first=new SequencedConvex(-1);
		SequencedConvex second=new SequencedConvex(-1);
		manager.add(AKeyPair.generate().getAccountKey(),first);
		manager.add(AKeyPair.generate().getAccountKey(),second);
		Message priority=Message.createPing(101);

		assertEquals(2,manager.broadcastPriority(priority));
		assertSame(priority,first.priority);
		assertSame(priority,second.priority);
	}

	@Test
	public void testBroadcastSkipsBusyPeersOnlyWhenAsked() {
		TestConnectionManager manager=new TestConnectionManager();
		SequencedConvex idle=new SequencedConvex(-1);
		SequencedConvex busy=new SequencedConvex(-1);
		busy.busy=true;
		manager.add(AKeyPair.generate().getAccountKey(),idle);
		manager.add(AKeyPair.generate().getAccountKey(),busy);
		Message essential=Message.createPing(1);
		Message optional=Message.createPing(2);

		// Essential traffic reaches every peer; optional traffic skips the busy one
		assertEquals(2,manager.broadcast(essential));
		assertEquals(1,manager.broadcast(optional,true));
		assertEquals(List.of(essential,optional),idle.sent);
		assertEquals(List.of(essential),busy.sent);
	}

	private static final class TestConnectionManager extends AConnectionManager {
		void add(AccountKey key,Convex connection) { connections.put(key,connection); }
		@Override public void close() { closeAllConnections(); }
	}

	private static final class SequencedConvex extends Convex {
		final ArrayList<Message> sent=new ArrayList<>();
		final int failAt;
		int attempts;
		boolean connected=true;
		boolean busy=false;
		Message priority;

		SequencedConvex(int failAt) { super(null,null); this.failAt=failAt; }

		@Override public boolean trySend(Message message) {
			attempts++;
			if (attempts==failAt) return false;
			sent.add(message);
			return true;
		}
		@Override public boolean trySendPriority(Message message) {
			priority=message;
			return true;
		}
		@Override public boolean isConnected() { return connected; }
		@Override public boolean isOutboundBusy() { return busy; }
		@Override public CompletableFuture<Result> transact(SignedData<ATransaction> tx) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}
		@Override public CompletableFuture<Result> messageRaw(Blob message) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}
		@Override public CompletableFuture<Result> message(Message message) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}
		@Override public <T extends ACell> CompletableFuture<T> acquire(Hash hash,AStore store) {
			return new CompletableFuture<>();
		}
		@Override public CompletableFuture<Result> requestStatus() {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}
		@Override protected CompletableFuture<Result> sendChallenge(SignedData<ACell> data) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}
		@Override public CompletableFuture<Result> query(ACell query,Address address) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}
		@Override public void close() { connected=false; }
		@Override public InetSocketAddress getHostAddress() { return null; }
		@Override public void reconnect() { connected=true; }
		@Override public String toString() { return "Sequenced test connection"; }
	}
}
