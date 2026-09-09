package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;

/** Exercises real maintenance and admission using controlled time and transport completion. */
public class LatticeConnectionManagerTest {
	private final AtomicLong now=new AtomicLong();
	private TestManager manager;

	@BeforeEach public void setUp() {
		now.set(0);
		manager=new TestManager(now);
	}

	@AfterEach public void close() { manager.close(); }

	private AccountKey candidate(int seed) {
		AccountKey key=AKeyPair.createSeeded(seed).getAccountKey();
		assertTrue(manager.updateDiscoveredPeer(key,Vectors.of(Strings.create("tcp://localhost:1")),1));
		return key;
	}

	private void fill(int count) {
		for (int i=0;i<100 && manager.getConnectionCount()<count;i++) {
			manager.maintainConnections();
			manager.completeDials();
		}
		manager.maintainConnections();
		assertEquals(count,manager.getConnectionCount());
	}

	@Test public void testAmbientTargetAndPendingReservations() {
		for (int i=0;i<256;i++) candidate(i);
		manager.maintainConnections();
		assertEquals(4,manager.dials.size());
		manager.maintainConnections();
		assertEquals(4,manager.dials.size(),"pending socket opens reserve their slots");
		fill(16);
		assertEquals(16,manager.openCount);
		var incumbents=manager.getConnections();
		for (int i=0;i<10;i++) manager.maintainConnections();
		assertEquals(incumbents,manager.getConnections(),"healthy ambient peers stay stable");
		assertEquals(256,manager.getDesiredPeers().size());
	}

	@Test public void testSlowDialDoesNotBlockOtherVacancies() {
		for (int i=0;i<30;i++) candidate(i);
		manager.maintainConnections();
		AccountKey slow=manager.dials.keySet().iterator().next();
		CompletableFuture<Convex> pending=manager.dials.remove(slow);
		for (int i=0;i<10;i++) {
			manager.completeDials();
			manager.maintainConnections();
		}
		assertEquals(15,manager.getConnectionCount());
		assertEquals(16,manager.openCount);
		pending.complete(new StubPeer(slow));
		assertEquals(16,manager.getConnectionCount());
	}

	@Test public void testDirectedActivityAddsSlotsButBroadcastDoesNot() {
		manager.setPeerTargets(2,1);
		for (int i=0;i<10;i++) candidate(i);
		fill(2);
		manager.broadcast(Message.createPing(1));
		manager.broadcastSequence(java.util.List.of(Message.createPing(2)));
		manager.maintainConnections();
		assertEquals(2,manager.openCount);
		AccountKey active=manager.getConnections().keySet().iterator().next();
		assertTrue(manager.trySendAuthenticated(active,Message.createPing(3)));
		fill(3);
		assertNotNull(manager.getConnection(active));
		now.addAndGet(LatticeConnectionManager.ACTIVE_RETENTION_MS+1);
		manager.maintainConnections();
		assertEquals(2,manager.getConnectionCount());
	}

	@Test public void testMostRecentActivePeerWins() {
		manager.setPeerTargets(0,1);
		AccountKey first=candidate(1),second=candidate(2);
		manager.markActive(first);
		fill(1);
		now.incrementAndGet();
		manager.markActive(second);
		manager.maintainConnections();
		manager.completeDials();
		assertNull(manager.getConnection(first));
		assertNotNull(manager.getConnection(second));
	}

	@Test public void testExplicitConnectionsExceedTargetsAndSurviveDiscoveryUpdates() {
		manager.setPeerTargets(0,0);
		for (int i=0;i<5;i++) {
			AccountKey key=candidate(i);
			manager.connectPeer(key,new InetSocketAddress("localhost",1));
			assertTrue(manager.updateDiscoveredPeer(key,Vectors.of(Strings.create("tcp://localhost:2")),2));
		}
		fill(5);
		manager.maintainConnections();
		assertEquals(5,manager.getConnectionCount());
	}

	@Test public void testOutstandingWorkIsNotTrimmedAndBootstrapIsNotActivity() {
		manager.setPeerTargets(1,1);
		candidate(1);
		fill(1);
		AccountKey key=manager.getConnections().keySet().iterator().next();
		CompletableFuture<Void> operation=new CompletableFuture<>();
		manager.withPeer(key,false,() -> operation);
		manager.setPeerTargets(0,1);
		manager.maintainConnections();
		assertNotNull(manager.getConnection(key),"unfinished bootstrap retains its route");
		operation.complete(null);
		manager.maintainConnections();
		assertNull(manager.getConnection(key),"bootstrap did not earn active retention");
	}

	@Test public void testSilentPeerIsReplacedBeforeItsRetryAndLateProbeIsHarmless() {
		manager.setPeerTargets(1,0);
		candidate(1); candidate(2);
		fill(1);
		AccountKey failed=manager.getConnections().keySet().iterator().next();
		StubPeer old=(StubPeer)manager.getConnection(failed);
		manager.respond=false;
		now.addAndGet(LatticeConnectionManager.QUIET_PROBE_MS);
		manager.maintainConnections();
		CompletableFuture<Void> probe=manager.probes.get(old);
		assertNotNull(probe);
		now.addAndGet(LatticeConnectionManager.PROBE_TIMEOUT_MS);
		manager.maintainConnections();
		assertFalse(old.isConnected());
		assertFalse(manager.dials.containsKey(failed),"failed candidate is in backoff");
		assertEquals(1,manager.dials.size(),"another candidate replaces it immediately");
		manager.completeDials();
		probe.complete(null);
		assertEquals(1,manager.getConnectionCount());
		assertNull(manager.getConnection(failed));
	}

	@Test public void testQuietPeerGetsTwoMinutesBeforeProbingAndFullGraceAfterError() {
		candidate(1); fill(1);
		AccountKey key=manager.getConnections().keySet().iterator().next();
		Convex peer=manager.getConnection(key);
		manager.respond=false;
		now.set(119_999);
		manager.maintainConnections();
		assertTrue(manager.probes.isEmpty(),"quiet peers are not probed during the first two minutes");
		now.incrementAndGet();
		manager.maintainConnections();
		CompletableFuture<Void> probe=manager.probes.get(peer);
		assertNotNull(probe);
		// The endpoint's own request timeout can be shorter than the liveness grace.
		now.addAndGet(8_000);
		probe.completeExceptionally(new java.util.concurrent.TimeoutException());
		manager.maintainConnections();
		assertSame(peer,manager.getConnection(key));
		now.set(149_999);
		manager.maintainConnections();
		assertSame(peer,manager.getConnection(key),"an early probe error cannot shorten the grace period");
		now.incrementAndGet();
		manager.maintainConnections();
		assertNull(manager.getConnection(key));
		assertFalse(peer.isConnected());
	}

	@Test public void testOngoingPropagationKeepsAmbientPeersWithoutProbesOrChurn() {
		for (int i=0;i<30;i++) candidate(i);
		fill(16);
		var incumbents=manager.getConnections();
		// Model an hour of normal root sync without advancing any active-peer lease.
		for (int i=0;i<120;i++) {
			now.addAndGet(LatticePropagator.ROOT_SYNC_INTERVAL);
			incumbents.forEach((key,peer) -> manager.received(key,peer));
			manager.maintainConnections();
			assertEquals(incumbents,manager.getConnections());
		}
		assertTrue(manager.probes.isEmpty());
		assertEquals(16,manager.openCount);
	}

	@Test public void testPropagationCancelsProbeEvenInSameClockTick() {
		AccountKey key=candidate(1); fill(1);
		Convex peer=manager.getConnection(key);
		manager.respond=false;
		now.addAndGet(LatticeConnectionManager.QUIET_PROBE_MS);
		manager.maintainConnections();
		CompletableFuture<Void> oldProbe=manager.probes.get(peer);
		assertNotNull(oldProbe);
		manager.received(key,peer);
		now.addAndGet(LatticeConnectionManager.PROBE_TIMEOUT_MS);
		manager.maintainConnections();
		assertSame(peer,manager.getConnection(key),"propagation satisfies liveness without a PING reply");
		now.addAndGet(LatticeConnectionManager.QUIET_PROBE_MS-LatticeConnectionManager.PROBE_TIMEOUT_MS);
		manager.maintainConnections();
		CompletableFuture<Void> newProbe=manager.probes.get(peer);
		assertNotSame(oldProbe,newProbe);
		oldProbe.completeExceptionally(new IllegalStateException("late failure"));
		assertSame(peer,manager.getConnection(key));
		assertFalse(newProbe.isDone());
		manager.received(key,peer);
		newProbe.completeExceptionally(new IllegalStateException("late failure after propagation"));
		now.addAndGet(LatticeConnectionManager.PROBE_TIMEOUT_MS);
		manager.maintainConnections();
		assertSame(peer,manager.getConnection(key));
	}

	@Test public void testPhysicalOutboundTrafficCountsOnlyForItsCurrentSocket() {
		AccountKey key=candidate(1);
		StubInbound physical=new StubInbound();
		ConnectedStubPeer peer=new ConnectedStubPeer(key,physical);
		manager.addPeer(key,peer).join();
		now.addAndGet(LatticeConnectionManager.QUIET_PROBE_MS);
		manager.received(key,physical);
		manager.maintainConnections();
		assertTrue(manager.probes.isEmpty(),"endpoint-decoded traffic refreshes the outbound client's health");
		manager.respond=false;
		now.addAndGet(LatticeConnectionManager.QUIET_PROBE_MS);
		StubInbound stale=new StubInbound();
		stale.setTrustedKey(key);
		manager.received(key,stale);
		manager.maintainConnections();
		assertNotNull(manager.probes.get(peer),"a different socket cannot refresh this route");
	}

	@Test public void testClosedPeerIsReplacedWithoutWaitingForIdleProbe() {
		manager.setPeerTargets(1,0);
		candidate(1); candidate(2); fill(1);
		AccountKey key=manager.getConnections().keySet().iterator().next();
		manager.getConnection(key).close();
		manager.maintainConnections();
		assertEquals(1,manager.dials.size());
		assertFalse(manager.dials.containsKey(key));
		manager.completeDials();
		assertEquals(1,manager.getConnectionCount());
		assertTrue(manager.probes.isEmpty());
	}

	@Test public void testReceiveTrafficDefersProbeWithoutBecomingActivity() {
		candidate(1); fill(1);
		AccountKey key=manager.getConnections().keySet().iterator().next();
		now.addAndGet(LatticeConnectionManager.QUIET_PROBE_MS);
		manager.received(key,manager.getConnection(key));
		manager.maintainConnections();
		assertTrue(manager.probes.isEmpty());
		manager.setPeerTargets(0,1);
		manager.maintainConnections();
		assertNull(manager.getConnection(key));
	}

	@Test public void testLateFailedProbeCannotCloseReplacement() {
		AccountKey key=candidate(1); fill(1);
		StubPeer old=(StubPeer)manager.getConnection(key);
		manager.respond=false;
		now.addAndGet(LatticeConnectionManager.QUIET_PROBE_MS);
		manager.maintainConnections();
		StubPeer replacement=new StubPeer(key);
		manager.addPeer(key,replacement).join();
		manager.probes.get(old).completeExceptionally(new IllegalStateException("old route failed"));
		assertSame(replacement,manager.getConnection(key));
		assertTrue(replacement.isConnected());
	}

	@Test public void testInboundRouteCountsWithoutBeingClosedByTrimming() {
		manager.setPeerTargets(1,0);
		AccountKey key=candidate(1);
		candidate(2);
		StubInbound inbound=new StubInbound();
		inbound.setTrustedKey(key);
		manager.upgradeInboundConnection(inbound);
		manager.maintainConnections();
		assertEquals(0,manager.openCount);
		manager.setPeerTargets(0,0);
		manager.maintainConnections();
		assertSame(inbound,manager.getUpgradedInboundConnection(key));
		manager.close();
		assertFalse(inbound.isClosed(),"listener retains physical ownership");
	}

	@Test public void testLateDialAfterCloseIsClosed() {
		candidate(1);
		manager.maintainConnections();
		var attempt=manager.dials.entrySet().iterator().next();
		manager.close();
		StubPeer late=new StubPeer(attempt.getKey());
		attempt.getValue().complete(late);
		assertFalse(late.isConnected());
		assertEquals(0,manager.getConnectionCount());
	}

	@Test public void testPendingAuthenticationReservesSlotsAndTimesOut() {
		manager.setKeyPair(AKeyPair.generate());
		for (int i=0;i<20;i++) candidate(i);
		manager.maintainConnections();
		var attempts=new ArrayList<>(manager.dials.entrySet());
		manager.dials.clear();
		for (var entry:attempts) entry.getValue().complete(new UnverifiedPeer(entry.getKey()));
		assertEquals(4,manager.getPendingConnectionCount());
		manager.maintainConnections();
		assertEquals(4,manager.openCount);
		now.addAndGet(LatticeConnectionManager.ADMISSION_TIMEOUT_MS);
		manager.maintainConnections();
		assertEquals(0,manager.getPendingConnectionCount());
		assertEquals(4,manager.dials.size());
		for (var entry:attempts) assertFalse(manager.dials.containsKey(entry.getKey()));
	}

	@Test public void testInboundProbeFailureIsRetiredByItsOwner() {
		AccountKey key=candidate(1);
		StubInbound inbound=new StubInbound();
		inbound.setTrustedKey(key);
		manager.setInboundProbe(connection -> new CompletableFuture<>(),AConnection::close);
		manager.upgradeInboundConnection(inbound);
		manager.respond=false;
		now.addAndGet(LatticeConnectionManager.QUIET_PROBE_MS);
		manager.maintainConnections();
		assertNotNull(manager.probes.get(inbound));
		now.addAndGet(LatticeConnectionManager.PROBE_TIMEOUT_MS);
		manager.maintainConnections();
		assertNull(manager.getUpgradedInboundConnection(key));
		assertTrue(inbound.isClosed());
	}

	private static class TestManager extends LatticeConnectionManager {
		final Map<AccountKey,CompletableFuture<Convex>> dials=new LinkedHashMap<>();
		final Map<Object,CompletableFuture<Void>> probes=new HashMap<>();
		int openCount;
		boolean respond=true;
		TestManager(AtomicLong now) { super(new MemoryStore(),now::get); }
		@Override CompletableFuture<Convex> openPeer(DesiredPeer peer) {
			openCount++;
			CompletableFuture<Convex> future=new CompletableFuture<>();
			dials.put(peer.peerKey,future);
			return future;
		}
		@Override CompletableFuture<?> probePeer(Object route) {
			CompletableFuture<Void> future=new CompletableFuture<>();
			probes.put(route,future);
			if (respond) future.complete(null);
			return future;
		}
		void completeDials() {
			for (var entry:new ArrayList<>(dials.entrySet())) {
				dials.remove(entry.getKey());
				entry.getValue().complete(new StubPeer(entry.getKey()));
			}
		}
	}

	private static class StubPeer extends Convex {
		boolean connected=true;
		StubPeer(AccountKey key) { super(null,null); setVerifiedPeer(key); }
		@Override public boolean isConnected() { return connected; }
		@Override public boolean trySend(Message message) { return connected; }
		@Override public void close() { connected=false; }
		@Override public void reconnect() { connected=true; }
		@Override public InetSocketAddress getHostAddress() { return null; }
		@Override public String toString() { return "Policy test peer"; }
		@Override public CompletableFuture<Result> transact(SignedData<ATransaction> tx) { throw new UnsupportedOperationException(); }
		@Override public CompletableFuture<Result> messageRaw(Blob message) { throw new UnsupportedOperationException(); }
		@Override public CompletableFuture<Result> message(Message message) { throw new UnsupportedOperationException(); }
		@Override public <T extends ACell> CompletableFuture<T> acquire(Hash hash,AStore store) { throw new UnsupportedOperationException(); }
		@Override public CompletableFuture<Result> requestStatus() { throw new UnsupportedOperationException(); }
		@Override protected CompletableFuture<Result> sendChallenge(SignedData<ACell> data) { throw new UnsupportedOperationException(); }
		@Override public CompletableFuture<Result> query(ACell query,Address address) { throw new UnsupportedOperationException(); }
	}

	private static class StubInbound extends AConnection {
		boolean closed;
		@Override public boolean sendMessage(Message message) { return !closed; }
		@Override public boolean trySendMessage(Message message) { return !closed; }
		@Override public InetSocketAddress getRemoteAddress() { return null; }
		@Override public boolean isClosed() { return closed; }
		@Override public void close() { closed=true; }
		@Override public long getReceivedCount() { return 0; }
	}

	private static class ConnectedStubPeer extends ConvexRemote {
		ConnectedStubPeer(AccountKey key,AConnection physical) {
			super(null,null);
			setConnection(physical);
			setVerifiedPeer(key);
		}
	}

	private static class UnverifiedPeer extends StubPeer {
		UnverifiedPeer(AccountKey key) { super(key); setVerifiedPeer(null); }
		@Override public CompletableFuture<AccountKey> verifyPeer(AccountKey expected,ACell context) {
			return new CompletableFuture<>();
		}
	}
}
