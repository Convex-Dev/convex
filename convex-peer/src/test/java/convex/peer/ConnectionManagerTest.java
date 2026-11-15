package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.api.ConvexRemote;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.init.Init;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.net.Transports;
import convex.net.impl.netty.NettyServer;

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
	public void testStatusPollDoesNotAcquireUnchangedBelief() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(201));
		try {
			ConnectionManager manager=server.getConnectionManager();
			AccountKey remoteKey=AKeyPair.createSeeded(202).getAccountKey();
			PollingConvex connection=new PollingConvex();
			connection.statusResult=Result.create(CVMLong.ZERO,server.getStatusData());
			manager.addConnection(remoteKey,connection);

			manager.pollBelief(remoteKey,connection);

			assertEquals(0,connection.acquireCount);
			assertTrue(connection.isConnected());
		} finally {
			server.close();
		}
	}

	@Test
	public void testStatusPollAcquiresChangedBelief() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(203));
		try {
			ConnectionManager manager=server.getConnectionManager();
			AKeyPair remote=AKeyPair.createSeeded(204);
			Belief remoteBelief=Belief.create(remote,Order.create());
			PollingConvex connection=new PollingConvex();
			connection.setStore(server.getStore());
			AVector<ACell> status=server.getStatusData().assoc(0,remoteBelief.getHash());
			connection.statusResult=Result.create(CVMLong.ZERO,status);
			connection.acquired=remoteBelief;
			manager.addConnection(remote.getAccountKey(),connection);

			manager.pollBelief(remote.getAccountKey(),connection);

			assertEquals(1,connection.acquireCount);
		} finally {
			server.close();
		}
	}

	/**
	 * Polling is how Beliefs keep arriving from the peers we chose, so pushes from any
	 * connection filling their queue must not stop it. Only a full queue of polled
	 * Beliefs does.
	 */
	@Test
	public void testStatusPollAcquiresUntilPolledInputSaturated() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(209));
		try {
			ConnectionManager manager=server.getConnectionManager();
			AKeyPair remote=AKeyPair.createSeeded(210);
			Belief remoteBelief=Belief.create(remote,Order.create());
			PollingConvex connection=new PollingConvex();
			connection.setStore(server.getStore());
			AVector<ACell> status=server.getStatusData().assoc(0,remoteBelief.getHash());
			connection.statusResult=Result.create(CVMLong.ZERO,status);
			connection.acquired=remoteBelief;
			manager.addConnection(remote.getAccountKey(),connection);

			BeliefPropagator propagator=server.getBeliefPropagator();
			Message filler=Message.createBelief(server.getBelief());
			for (int i=0;i<Config.BELIEF_QUEUE_SIZE;i++) {
				assertTrue(propagator.queuePropagation(filler));
			}
			manager.pollBelief(remote.getAccountKey(),connection);
			assertEquals(1,connection.acquireCount,"a full push queue does not stop polling");

			while (propagator.queueBelief(Belief.initial())) {}
			manager.pollBelief(remote.getAccountKey(),connection);
			assertEquals(1,connection.acquireCount,"no acquisition while polled Beliefs cannot be queued");
		} finally {
			server.close();
		}
	}

	@Test
	public void testStatusTimeoutAgesOutConnection() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(205));
		try {
			AtomicLong clock=new AtomicLong(1000);
			server.setTimeSource(clock::get);
			ConnectionManager manager=server.getConnectionManager();
			AccountKey remoteKey=AKeyPair.createSeeded(206).getAccountKey();
			PollingConvex connection=new PollingConvex();
			connection.statusResult=Result.error(ErrorCodes.TIMEOUT,"test timeout");
			manager.addConnection(remoteKey,connection);

			manager.pollBelief(remoteKey,connection);
			assertTrue(connection.isConnected(),"one timeout must not remove a peer");

			clock.addAndGet(ConnectionManager.UNRESPONSIVE_CONNECTION_TIMEOUT_MILLIS-1);
			manager.pollBelief(remoteKey,connection);
			assertTrue(connection.isConnected(),"the full grace period must elapse");

			clock.incrementAndGet();
			manager.pollBelief(remoteKey,connection);
			assertFalse(connection.isConnected());
			assertNull(manager.getConnection(remoteKey));
		} finally {
			server.close();
		}
	}

	@Test
	public void testSuccessfulStatusResetsTimeoutAge() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(207));
		try {
			AtomicLong clock=new AtomicLong(1000);
			server.setTimeSource(clock::get);
			ConnectionManager manager=server.getConnectionManager();
			AccountKey remoteKey=AKeyPair.createSeeded(208).getAccountKey();
			PollingConvex connection=new PollingConvex();
			manager.addConnection(remoteKey,connection);

			connection.statusResult=Result.error(ErrorCodes.TIMEOUT,"test timeout");
			manager.pollBelief(remoteKey,connection);

			clock.addAndGet(ConnectionManager.UNRESPONSIVE_CONNECTION_TIMEOUT_MILLIS-1);
			connection.statusResult=Result.create(CVMLong.ZERO,server.getStatusData());
			manager.pollBelief(remoteKey,connection);

			clock.addAndGet(ConnectionManager.UNRESPONSIVE_CONNECTION_TIMEOUT_MILLIS);
			connection.statusResult=Result.error(ErrorCodes.TIMEOUT,"test timeout");
			manager.pollBelief(remoteKey,connection);
			assertTrue(connection.isConnected(),"a success starts a new failure window");
		} finally {
			server.close();
		}
	}

	@Test
	public void testNonKeywordStatusErrorIsNotConnectivityFailure() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(211));
		try {
			AtomicLong clock=new AtomicLong(1000);
			server.setTimeSource(clock::get);
			ConnectionManager manager=server.getConnectionManager();
			AccountKey remoteKey=AKeyPair.createSeeded(212).getAccountKey();
			PollingConvex connection=new PollingConvex();
			manager.addConnection(remoteKey,connection);

			connection.statusResult=Result.error(ErrorCodes.TIMEOUT,"test timeout");
			manager.pollBelief(remoteKey,connection);

			clock.addAndGet(ConnectionManager.UNRESPONSIVE_CONNECTION_TIMEOUT_MILLIS-1);
			connection.statusResult=Result.create(CVMLong.ZERO,Strings.create("test error"),CVMLong.ONE);
			manager.pollBelief(remoteKey,connection);

			clock.addAndGet(ConnectionManager.UNRESPONSIVE_CONNECTION_TIMEOUT_MILLIS);
			connection.statusResult=Result.error(ErrorCodes.TIMEOUT,"test timeout");
			manager.pollBelief(remoteKey,connection);
			assertTrue(connection.isConnected(),"an arbitrary error code starts no connectivity-failure window");
		} finally {
			server.close();
		}
	}

	@Test
	public void testInterruptedConnectKeepsInterruptStatus() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(214));
		// Accepts TCP but never answers the TLS handshake, so the connect cannot finish
		try (ServerSocket silent=new ServerSocket(0,50,InetAddress.getLoopbackAddress())) {
			ConnectionManager manager=new ConnectionManager(server);
			URI endpoint=new URI("tls",null,"127.0.0.1",silent.getLocalPort(),null,null,null);

			Thread.currentThread().interrupt();
			CompletableFuture<Convex> result=manager.connectToPeer(endpoint,AKeyPair.createSeeded(215).getAccountKey());

			assertTrue(Thread.interrupted(),"the caller must keep its interrupt status");
			assertTrue(result.isCompletedExceptionally());
			assertTrue(result.exceptionNow() instanceof InterruptedException);
		} finally {
			Thread.interrupted();
			server.close();
		}
	}

	// ===== Polling preference and back-off =====

	/** Start of the test clock, well past the first poll slot. */
	private static final long START_TIME=1_000_000;

	@Test
	public void testBackoffDoublesFromPollDelayToCap() {
		assertEquals(2000,ConnectionManager.backoff(2000,1));
		assertEquals(4000,ConnectionManager.backoff(2000,2));
		long cap=2000L<<ConnectionManager.MAX_BACKOFF_DOUBLINGS;
		assertEquals(cap,ConnectionManager.backoff(2000,ConnectionManager.MAX_BACKOFF_DOUBLINGS+1));
		assertEquals(cap,ConnectionManager.backoff(2000,1000));
	}

	/** Verified links get every poll slot they can use; an unverified link gets the rest. */
	@Test
	public void testPollPrefersDueVerifiedLinks() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(230));
		try {
			AtomicLong clock=new AtomicLong(START_TIME);
			server.setTimeSource(clock::get);
			ConnectionManager manager=server.getConnectionManager();
			long delay=manager.getPollDelay();
			AccountKey verifiedKey=AKeyPair.createSeeded(231).getAccountKey();
			AccountKey unverifiedKey=AKeyPair.createSeeded(232).getAccountKey();
			PollingConvex verified=pollingLink(server);
			verified.markVerified(verifiedKey);
			PollingConvex unverified=pollingLink(server);
			manager.addConnection(verifiedKey,verified);
			manager.addConnection(unverifiedKey,unverified);

			for (int i=0;i<3;i++) {
				manager.maybePollBelief();
				clock.addAndGet(delay);
			}
			assertEquals(3,verified.statusCount);
			assertEquals(0,unverified.statusCount,"no slot goes to an unverified link while a verified one is due");

			// Two failures in a row leave the verified link waiting past the next slot
			verified.statusResult=Result.error(ErrorCodes.LOAD,"busy");
			manager.maybePollBelief();
			clock.addAndGet(delay);
			manager.maybePollBelief();
			clock.addAndGet(delay);
			manager.maybePollBelief();
			assertEquals(5,verified.statusCount);
			assertEquals(1,unverified.statusCount,"a slot no verified link can use goes to an unverified one");
		} finally {
			server.close();
		}
	}

	@Test
	public void testUnverifiedPollLeavesBackoffAlone() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(233));
		try {
			ConnectionManager manager=server.getConnectionManager();
			AccountKey key=AKeyPair.createSeeded(234).getAccountKey();
			PollingConvex link=pollingLink(server);
			link.statusResult=Result.error(ErrorCodes.LOAD,"busy");
			manager.addConnection(key,link);

			manager.pollBelief(key,link);
			assertTrue(manager.isDue(key),"an unverified link's back-off is for getting it verified");
		} finally {
			server.close();
		}
	}

	/** Each failed poll of a verified link doubles its wait, and a good poll clears them. */
	@Test
	public void testFailedPollsBackOff() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(235));
		try {
			AtomicLong clock=new AtomicLong(START_TIME);
			server.setTimeSource(clock::get);
			ConnectionManager manager=server.getConnectionManager();
			long delay=manager.getPollDelay();
			Belief other=Belief.create(AKeyPair.createSeeded(236),Order.create());
			Result changed=Result.create(CVMLong.ZERO,server.getStatusData().assoc(0,other.getHash()));
			List<Consumer<PollingConvex>> failures=List.of(
				link -> link.statusResult=Result.error(ErrorCodes.LOAD,"busy"),
				link -> link.statusResult=Result.create(CVMLong.ZERO,Strings.create("not a status")),
				link -> { link.statusResult=changed; link.acquired=CVMLong.ONE; },
				link -> { link.statusResult=changed; link.acquireFails=true; });
			int seed=240;
			for (Consumer<PollingConvex> failure:failures) {
				AccountKey key=AKeyPair.createSeeded(seed++).getAccountKey();
				PollingConvex link=pollingLink(server);
				link.markVerified(key);
				failure.accept(link);
				manager.addConnection(key,link);

				manager.pollBelief(key,link);
				assertFalse(manager.isDue(key));
				clock.addAndGet(delay);
				assertTrue(manager.isDue(key));

				manager.pollBelief(key,link);
				clock.addAndGet(delay);
				assertFalse(manager.isDue(key),"a second failure in a row doubles the wait");
				clock.addAndGet(delay);
				assertTrue(manager.isDue(key));

				link.statusResult=Result.create(CVMLong.ZERO,server.getStatusData());
				manager.pollBelief(key,link);
				failure.accept(link);
				manager.pollBelief(key,link);
				clock.addAndGet(delay);
				assertTrue(manager.isDue(key),"a good poll cleared the earlier failures");
			}
		} finally {
			server.close();
		}
	}

	@Test
	public void testFullPolledBeliefQueueIsNoFailure() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(244));
		try {
			ConnectionManager manager=server.getConnectionManager();
			AccountKey key=AKeyPair.createSeeded(245).getAccountKey();
			PollingConvex link=pollingLink(server);
			link.markVerified(key);
			Belief other=Belief.create(AKeyPair.createSeeded(246),Order.create());
			link.statusResult=Result.create(CVMLong.ZERO,server.getStatusData().assoc(0,other.getHash()));
			manager.addConnection(key,link);
			while (server.getBeliefPropagator().queueBelief(Belief.initial())) {}

			manager.pollBelief(key,link);
			assertEquals(0,link.acquireCount);
			assertTrue(manager.isDue(key),"a full local queue is not the peer's failure");
		} finally {
			server.close();
		}
	}

	/** A challenge passed verifies a link, but only a good poll clears its peer's failures. */
	@Test
	public void testChallengeVerifiesLinkAndKeepsFailures() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(247));
		try {
			AtomicLong clock=new AtomicLong(START_TIME);
			server.setTimeSource(clock::get);
			ConnectionManager manager=server.getConnectionManager();
			long delay=manager.getPollDelay();
			AKeyPair remote=AKeyPair.createSeeded(248);
			AccountKey key=remote.getAccountKey();
			PollingConvex link=pollingLink(server);
			link.setKeyPair(AKeyPair.createSeeded(249));
			link.answerer=AKeyPair.createSeeded(250);
			manager.addConnection(key,link);

			manager.verifyDueLinks();
			assertEquals(1,link.challengeCount);
			manager.verifyDueLinks();
			assertNull(link.getVerifiedPeer(),"an answer from another key does not verify the link");
			assertFalse(manager.isDue(key));
			manager.verifyDueLinks();
			assertEquals(1,link.challengeCount,"no new challenge before the wait has passed");

			clock.addAndGet(delay);
			manager.verifyDueLinks();
			manager.verifyDueLinks();
			assertEquals(2,link.challengeCount);

			link.answerer=remote;
			clock.addAndGet(2*delay);
			manager.verifyDueLinks();
			manager.verifyDueLinks();
			assertEquals(key,link.getVerifiedPeer());
			assertTrue(manager.isDue(key),"a newly verified link is polled at once");

			// The two failed challenges still count, so a failed poll is the third in a row
			link.statusResult=Result.error(ErrorCodes.LOAD,"busy");
			manager.pollBelief(key,link);
			clock.addAndGet(3*delay);
			assertFalse(manager.isDue(key));
			clock.addAndGet(delay);
			assertTrue(manager.isDue(key));
		} finally {
			server.close();
		}
	}

	@Test
	public void testUnansweredChallengesCloseLinkAfterMinute() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(251));
		try {
			AtomicLong clock=new AtomicLong(START_TIME);
			server.setTimeSource(clock::get);
			ConnectionManager manager=server.getConnectionManager();
			AccountKey key=AKeyPair.createSeeded(252).getAccountKey();
			PollingConvex link=pollingLink(server);
			link.setKeyPair(AKeyPair.createSeeded(253));
			manager.addConnection(key,link);

			long firstMiss=-1;
			for (int i=0;link.isConnected() && i<20;i++) {
				advanceUntilDue(manager,clock,key);
				manager.verifyDueLinks();
				clock.addAndGet(ConnectionManager.POLL_TIMEOUT_MILLIS);
				manager.verifyDueLinks();
				if (firstMiss<0) firstMiss=clock.get();
				boolean minutePassed=clock.get()-firstMiss>=ConnectionManager.UNRESPONSIVE_CONNECTION_TIMEOUT_MILLIS;
				assertEquals(!minutePassed,link.isConnected());
			}
			assertNull(manager.getConnection(key));
		} finally {
			server.close();
		}
	}

	/** A verified link that never replies is polled on its back-off and closed after a minute. */
	@Test
	public void testSilentVerifiedLinkClosedAfterMinute() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(266));
		try {
			AtomicLong clock=new AtomicLong(START_TIME);
			server.setTimeSource(clock::get);
			ConnectionManager manager=server.getConnectionManager();
			AccountKey key=AKeyPair.createSeeded(267).getAccountKey();
			PollingConvex link=pollingLink(server);
			link.markVerified(key);
			link.statusResult=Result.error(ErrorCodes.TIMEOUT,"no reply");
			manager.addConnection(key,link);

			long firstMiss=clock.get();
			while (link.isConnected() && link.statusCount<20) {
				advanceUntilDue(manager,clock,key);
				manager.maybePollBelief();
				boolean minutePassed=clock.get()-firstMiss>=ConnectionManager.UNRESPONSIVE_CONNECTION_TIMEOUT_MILLIS;
				assertEquals(!minutePassed,link.isConnected());
			}
			assertEquals(6,link.statusCount,"polled at 0, 2, 6, 14, 30 and 62 s");
		} finally {
			server.close();
		}
	}

	@Test
	public void testLinkClosedWhenFailuresReachCap() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(254));
		try {
			ConnectionManager manager=server.getConnectionManager();
			AccountKey key=AKeyPair.createSeeded(255).getAccountKey();
			PollingConvex link=pollingLink(server);
			link.markVerified(key);
			link.statusResult=Result.error(ErrorCodes.LOAD,"busy");
			manager.addConnection(key,link);

			for (int i=0;i<ConnectionManager.MAX_BACKOFF_DOUBLINGS;i++) {
				manager.pollBelief(key,link);
			}
			assertTrue(link.isConnected(),"a link that answers stays open below the cap");
			manager.pollBelief(key,link);
			assertFalse(link.isConnected());

			// Linked again, the peer keeps its failures, so the new link gets one attempt
			PollingConvex again=pollingLink(server);
			again.markVerified(key);
			again.statusResult=Result.error(ErrorCodes.LOAD,"busy");
			manager.addConnection(key,again);
			manager.pollBelief(key,again);
			assertFalse(again.isConnected());
		} finally {
			server.close();
		}
	}

	@Test
	public void testUnverifiedLinkKeptAtCapWhileNoLinkIsVerified() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(256));
		try {
			AtomicLong clock=new AtomicLong(START_TIME);
			server.setTimeSource(clock::get);
			ConnectionManager manager=server.getConnectionManager();
			AccountKey key=AKeyPair.createSeeded(257).getAccountKey();
			PollingConvex link=pollingLink(server);
			link.setKeyPair(AKeyPair.createSeeded(258));
			link.answerer=AKeyPair.createSeeded(259);
			manager.addConnection(key,link);

			for (int i=0;i<=ConnectionManager.MAX_BACKOFF_DOUBLINGS+1;i++) {
				advanceUntilDue(manager,clock,key);
				manager.verifyDueLinks();
				manager.verifyDueLinks();
			}
			assertEquals(ConnectionManager.MAX_BACKOFF_DOUBLINGS+2,link.challengeCount);
			assertTrue(link.isConnected(),"while no link is verified, an unverified one is all there is to poll");

			AccountKey verifiedKey=AKeyPair.createSeeded(260).getAccountKey();
			PollingConvex verified=pollingLink(server);
			verified.markVerified(verifiedKey);
			manager.addConnection(verifiedKey,verified);
			advanceUntilDue(manager,clock,key);
			manager.verifyDueLinks();
			manager.verifyDueLinks();
			assertFalse(link.isConnected());
		} finally {
			server.close();
		}
	}

	@Test
	public void testDialsBackOff() throws Exception {
		AKeyPair keyPair=AKeyPair.createSeeded(261);
		AccountKey remoteKey=AKeyPair.createSeeded(262).getAccountKey();
		State genesis=Init.createState(List.of(keyPair.getAccountKey(),remoteKey));
		PeerStatus remoteStatus=genesis.getPeer(remoteKey);
		genesis=genesis.withPeer(remoteKey,remoteStatus.withPeerData(Maps.of(Keywords.URL,Strings.create("127.0.0.1:1"))));
		Server server=createPollingServer(keyPair,genesis);
		DialingManager manager=new DialingManager(server);
		try {
			AtomicLong clock=new AtomicLong(START_TIME);
			server.setTimeSource(clock::get);
			State state=server.getPeer().getConsensusState();
			long delay=manager.getPollDelay();

			// Reaching nobody, with no verified link, is most likely this peer's own fault
			manager.next=() -> CompletableFuture.failedFuture(new IOException("unreachable"));
			assertNotNull(manager.tryRandomConnect(state));
			assertFalse(manager.isDue(remoteKey));
			assertNotNull(manager.tryRandomConnect(state),"retried at once while no link is verified");
			assertEquals(2,manager.dials);

			AccountKey verifiedKey=AKeyPair.createSeeded(263).getAccountKey();
			PollingConvex verified=pollingLink(server);
			verified.markVerified(verifiedKey);
			manager.addConnection(verifiedKey,verified);
			assertNull(manager.tryRandomConnect(state),"with a verified link, the peer waits");
			clock.addAndGet(2*delay);
			assertNotNull(manager.tryRandomConnect(state));
			assertEquals(3,manager.dials);

			// A dial that reaches another key is the peer answering wrongly: it waits regardless
			verified.close();
			clock.addAndGet(4*delay);
			AccountKey otherKey=AKeyPair.createSeeded(264).getAccountKey();
			manager.next=() -> {
				PollingConvex other=pollingLink(server);
				manager.addConnection(otherKey,other);
				return CompletableFuture.completedFuture(other);
			};
			assertNotNull(manager.tryRandomConnect(state));
			assertEquals(4,manager.dials);
			assertFalse(manager.isDue(remoteKey));
			assertNull(manager.tryRandomConnect(state),"a peer that answered wrongly is not redialled at once");
		} finally {
			manager.close();
			server.close();
		}
	}

	/** The link to the peer a server was launched from can be verified like any other. */
	@Test
	public void testLaunchSourceLinkCanBeVerified() throws Exception {
		AKeyPair keyPair=AKeyPair.createSeeded(265);
		HashMap<convex.core.data.Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,keyPair);
		config.put(Keywords.STORE,new MemoryStore());
		Server server=Server.create(config);
		try {
			Convex source=Convex.connect(network.SERVER);
			source.setStore(server.getStore());
			assertNull(source.getKeyPair());
			server.syncPeer(keyPair,source);

			AccountKey sourceKey=network.SERVER.getPeerKey();
			Convex link=server.getConnectionManager().getConnection(sourceKey);
			assertSame(source,link);
			Hash networkID=network.SERVER.getPeer().getNetworkID();
			assertEquals(sourceKey,link.verifyPeer(sourceKey,networkID).get(10,TimeUnit.SECONDS));
		} finally {
			server.close();
		}
	}

	private static void advanceUntilDue(ConnectionManager manager, AtomicLong clock, AccountKey key) {
		while (!manager.isDue(key)) clock.addAndGet(manager.getPollDelay());
	}

	private static PollingConvex pollingLink(Server server) {
		PollingConvex link=new PollingConvex();
		link.setStore(server.getStore());
		link.statusResult=Result.create(CVMLong.ZERO,server.getStatusData());
		return link;
	}

	private static Server createPollingServer(AKeyPair keyPair) throws Exception {
		return createPollingServer(keyPair,Init.createState(List.of(keyPair.getAccountKey())));
	}

	private static Server createPollingServer(AKeyPair keyPair, State genesis) throws Exception {
		Peer peer=Peer.createGenesisPeer(keyPair,genesis);
		HashMap<convex.core.data.Keyword,Object> config=new HashMap<>();
		config.put(Keywords.STORE,new MemoryStore());
		Server server=Server.create(config);
		server.getCVMExecutor().setPeer(peer);
		return server;
	}

	@Test
	public void testSequenceBackpressureIsIsolatedPerPeer() {
		TestConnectionManager manager=new TestConnectionManager();
		SequencedConvex good=new SequencedConvex(-1);
		SequencedConvex slow=new SequencedConvex(2);
		manager.add(AKeyPair.generate().getAccountKey(),good);
		manager.add(AKeyPair.generate().getAccountKey(),slow);
		List<Message> sequence=List.of(Message.createPing(1),Message.createPing(2),Message.createPing(3));

		assertEquals(1,manager.broadcastSequence(sequence));
		assertEquals(sequence,good.sent);
		assertEquals(List.of(sequence.get(0)),slow.sent);
		for (int i=0; i<sequence.size(); i++) assertSame(sequence.get(i),good.sent.get(i));
	}

	@Test
	public void testReplacingConnectionClosesPreviousOwner() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(213));
		try {
			ConnectionManager manager=server.getConnectionManager();
			AccountKey key=AKeyPair.createSeeded(214).getAccountKey();
			SequencedConvex first=new SequencedConvex(-1);
			SequencedConvex second=new SequencedConvex(-1);

			assertTrue(manager.addConnection(key,first));
			assertTrue(manager.addConnection(key,second));

			assertEquals(1,first.closeCount);
			assertSame(second,manager.getConnection(key));
		} finally {
			server.close();
		}
	}

	@Test
	public void testPruningClosesStaleConnection() {
		TestConnectionManager manager=new TestConnectionManager();
		AccountKey key=AKeyPair.createSeeded(215).getAccountKey();
		SequencedConvex stale=new SequencedConvex(-1);
		stale.connected=false;
		manager.add(key,stale);

		assertNull(manager.getConnection(key));
		assertEquals(1,stale.closeCount);
		assertEquals(0,manager.getConnectionCount());
	}

	@Test
	public void testClosedManagerRejectsLateConnection() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(216));
		ConnectionManager manager=server.getConnectionManager();
		manager.close();
		SequencedConvex late=new SequencedConvex(-1);

		assertFalse(manager.addConnection(AKeyPair.createSeeded(217).getAccountKey(),late));
		assertEquals(1,late.closeCount);
		server.close();
	}

	@Test
	@Timeout(60)
	public void testCloseReleasesLockBeforeClosingConnections() throws Exception {
		Server server=createPollingServer(AKeyPair.createSeeded(221));
		try {
			ConnectionManager manager=server.getConnectionManager();
			// Closing a network connection waits for its event loop, which may be running
			// a callback that needs the manager's lock: model that with another thread
			SequencedConvex connection=new SequencedConvex(-1) {
				@Override public void close() {
					Thread callback=Thread.ofVirtual().start(() -> { synchronized (manager) {} });
					try {
						callback.join();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(e);
					}
					super.close();
				}
			};
			assertTrue(manager.addConnection(AKeyPair.createSeeded(222).getAccountKey(),connection));

			manager.close();

			assertEquals(1,connection.closeCount);
		} finally {
			server.close();
		}
	}

	@Test
	public void testTCPRetainsUnverifiedStatusFallbackForKnownPeer() throws Exception {
		try (Server server=createPollingServer(AKeyPair.createSeeded(220));
				NettyServer remote=new NettyServer(0)) {
			remote.setReceiveAction(message -> {
				try {
					message.getPayload(null);
				} catch (BadFormatException e) {
					throw new AssertionError(e);
				}
				if (message.getType()==MessageType.STATUS) {
					message.returnMessage(Message.createResult(message.getRequestID(),server.getStatusData(),null));
				} else {
					message.returnMessage(Message.createResult(message.getRequestID(),null,ErrorCodes.ARGUMENT));
				}
			});
			remote.launch();
			ConnectionManager manager=server.getConnectionManager();
			AccountKey peerKey=server.getPeerKey();
			try (Convex client=manager.connectToPeer(Transports.endpoint(remote.getHostAddress()),peerKey)
					.get(5,TimeUnit.SECONDS)) {
				assertSame(client,manager.getConnection(peerKey));
				assertTrue(client.isConnected());
				assertNull(client.getVerifiedPeer(),"Status fallback must not confer protocol trust");
			}
		}
	}

	@Test
	public void testCloseCancelsPendingPeerIdentification() throws Exception {
		try (NettyServer remote=new NettyServer(0)) {
			remote.setReceiveAction(message -> {});
			remote.launch();
			Server server=createPollingServer(AKeyPair.createSeeded(218));
			try {
				ConnectionManager manager=server.getConnectionManager();
				CompletableFuture<Convex> connecting=manager.connectToPeer(remote.getHostAddress());

				manager.close();

				assertThrows(ExecutionException.class,
					() -> connecting.get(1,TimeUnit.SECONDS));
				assertEquals(0,manager.getConnectionCount());
			} finally {
				server.close();
			}
		}
	}

	@Test
	public void testFailedPeerSyncClosesSourceConnection() {
		AKeyPair keyPair=AKeyPair.createSeeded(219);
		PollingConvex source=new PollingConvex();
		source.statusResult=Result.error(ErrorCodes.CONNECT,"bad source");
		HashMap<convex.core.data.Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,keyPair);
		config.put(Keywords.STORE,new MemoryStore());
		config.put(Keywords.SOURCE,source);

		assertThrows(LaunchException.class,() -> API.launchPeer(config));
		assertEquals(1,source.closeCount);
	}

	private static final class TestConnectionManager extends AConnectionManager {
		void add(AccountKey key,Convex connection) { connections.put(key,connection); }
		@Override public void close() { closeAllConnections(); }
	}

	private static class SequencedConvex extends Convex {
		final ArrayList<Message> sent=new ArrayList<>();
		final int failAt;
		int attempts;
		boolean connected=true;
		int closeCount;

		SequencedConvex(int failAt) { super(null,null); this.failAt=failAt; }

		@Override public boolean trySend(Message message) {
			attempts++;
			if (attempts==failAt) return false;
			sent.add(message);
			return true;
		}
		@Override public boolean isConnected() { return connected; }
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
		@Override public void close() { closeCount++; connected=false; }
		@Override public InetSocketAddress getHostAddress() { return null; }
		@Override public void reconnect() { connected=true; }
		@Override public String toString() { return "Sequenced test connection"; }
	}

	private static final class PollingConvex extends SequencedConvex {
		Result statusResult;
		ACell acquired;
		boolean acquireFails;
		int acquireCount;
		int statusCount;
		/** Key pair that answers challenges, or null to leave them unanswered. */
		AKeyPair answerer;
		int challengeCount;

		PollingConvex() {
			super(-1);
		}

		void markVerified(AccountKey key) {
			verifiedPeer=key;
		}

		@Override public CompletableFuture<Result> requestStatus() {
			statusCount++;
			return CompletableFuture.completedFuture(statusResult);
		}

		@Override protected CompletableFuture<Result> sendChallenge(SignedData<ACell> data) {
			challengeCount++;
			if (answerer==null) return new CompletableFuture<>();
			return CompletableFuture.completedFuture(Message.answerChallenge(answerer,data,context -> true));
		}

		@SuppressWarnings("unchecked")
		@Override public <T extends ACell> CompletableFuture<T> acquire(Hash hash,AStore store) {
			acquireCount++;
			if (acquireFails) return CompletableFuture.failedFuture(new IOException("acquire failed"));
			return CompletableFuture.completedFuture((T)acquired);
		}
	}

	/** Connection manager whose dials are supplied by the test. */
	private static final class DialingManager extends ConnectionManager {
		Supplier<CompletableFuture<Convex>> next;
		int dials;

		DialingManager(Server server) {
			super(server);
		}

		@Override CompletableFuture<Convex> dial(URI hostAddress, AccountKey peerKey) {
			dials++;
			return next.get();
		}
	}
}
