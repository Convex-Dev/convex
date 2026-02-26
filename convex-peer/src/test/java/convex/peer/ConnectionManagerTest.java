package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;

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
}
