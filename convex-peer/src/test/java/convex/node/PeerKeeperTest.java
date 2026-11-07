package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.auth.did.DIDResolution;
import convex.auth.did.DIDResolver;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.util.JSON;
import convex.net.PeerConnectionException;
import convex.net.PeerName;
import convex.net.PeerResolver;
import convex.net.PeerTarget;

/** Exercises keeping a named peer with controlled time, scheduling and connection outcomes. */
public class PeerKeeperTest {

	private static final long PERIOD=60_000;
	private static final AccountKey KEY=AKeyPair.createSeeded(301).getAccountKey();
	private static final AccountKey ROTATED=AKeyPair.createSeeded(302).getAccountKey();

	/** Resolves transport names, which need no DID resolution. */
	private static final PeerResolver TRANSPORT=new PeerResolver(DIDResolver.KEY,null);

	// One instance serves the class's tests, which run in turn, so each starts afresh
	private AtomicLong now;
	private ArrayDeque<Runnable> scheduled;
	/** Every retry scheduled, as the keeper sees it pending. */
	private List<CompletableFuture<Void>> retries;
	private RecordingManager manager;

	@BeforeEach public void setUp() {
		now=new AtomicLong();
		scheduled=new ArrayDeque<>();
		retries=new ArrayList<>();
		manager=new RecordingManager();
	}

	@AfterEach public void close() { manager.close(); }

	private PeerKeeper keeper(PeerResolver resolver,String name) {
		return new PeerKeeper(manager,resolver,PeerResolver.parse(name),PERIOD,now::get,
			(delay,task) -> {
				scheduled.add(task);
				CompletableFuture<Void> retry=new CompletableFuture<>();
				retries.add(retry);
				return retry;
			});
	}

	private void runScheduled() {
		Runnable task=scheduled.poll();
		assertNotNull(task,"a retry is scheduled");
		task.run();
	}

	@Test public void retriesUntilThePeriodEndsThenGivesUp() {
		PeerKeeper keeper=keeper(TRANSPORT,"tcp://localhost:1");
		keeper.start(null);
		manager.fail();
		for (int i=0; i<3; i++) {
			now.addAndGet(PERIOD/4);
			runScheduled();
			manager.fail();
		}
		assertFalse(keeper.kept.isDone(),"still within the period");
		now.addAndGet(PERIOD/4);
		runScheduled();
		manager.fail();
		assertTrue(keeper.kept.isCompletedExceptionally(),"given up after a period without success");
		assertTrue(scheduled.isEmpty(),"nothing more is tried");
		assertEquals(5,manager.targets.size());
	}

	@Test public void resolvesTheNameAgainBeforeEachAttempt() {
		AtomicInteger resolutions=new AtomicInteger();
		AMap<AString,ACell> first=document(KEY);
		AMap<AString,ACell> second=document(ROTATED);
		PeerResolver resolver=new PeerResolver(did -> CompletableFuture.completedFuture(
			DIDResolution.of((resolutions.incrementAndGet()==1) ? first : second,null)),null);
		PeerKeeper keeper=keeper(resolver,"did:web:files.example.com");
		keeper.start(null);
		manager.fail();
		runScheduled();

		assertEquals(2,resolutions.get());
		assertEquals(Vectors.of(KEY),manager.targets.get(0).keys());
		assertEquals(Vectors.of(ROTATED),manager.targets.get(1).keys(),"a rotated key is picked up");
	}

	@Test public void reconnectsAfterALostRouteWithANewPeriod() {
		PeerKeeper keeper=keeper(TRANSPORT,"tcp://localhost:1");
		keeper.start(null);
		manager.admit(KEY);
		assertEquals(1,manager.targets.size());

		now.addAndGet(PERIOD*10);
		manager.loseRoute(KEY);
		assertEquals(2,manager.targets.size(),"connects again at once");
		assertEquals(Vectors.of(KEY),manager.targets.get(1).keys(),"a transport name is held to the key it first proved");
		manager.fail();
		assertFalse(keeper.kept.isDone(),"a lost route starts a new period");
		assertEquals(1,scheduled.size());
	}

	@Test public void startsFromAnAdmittedConnection() {
		PeerKeeper keeper=keeper(TRANSPORT,"tcp://localhost:1");
		keeper.start(new StubPeer(KEY));
		assertTrue(manager.targets.isEmpty(),"not dialled while the route lasts");
		manager.loseRoute(KEY);
		assertEquals(Vectors.of(KEY),manager.targets.get(0).keys());
		assertFalse(keeper.kept.isDone());
	}

	@Test public void cancellingStopsKeepingAndReleasesTheRoute() {
		PeerKeeper keeper=keeper(TRANSPORT,"tcp://localhost:1");
		keeper.start(null);
		manager.admit(KEY);
		keeper.kept.cancel(false);
		assertEquals(List.of(KEY),manager.released);
		manager.loseRoute(KEY);
		assertEquals(1,manager.targets.size(),"no reconnection once stopped");

		PeerKeeper failing=keeper(TRANSPORT,"tcp://localhost:1");
		failing.start(null);
		manager.fail();
		failing.kept.cancel(false);
		assertTrue(retries.get(0).isCancelled(),"stopping cancels the pending retry and its timer");
		// A retry already running when stopped makes no attempt
		runScheduled();
		assertEquals(2,manager.targets.size(),"no attempt once stopped");
	}

	/** Giving up reports the connection failure that ended the period. */
	@Test public void givingUpReportsAConnectionFailure() {
		PeerKeeper keeper=keeper(TRANSPORT,"tcp://localhost:1");
		keeper.start(null);
		now.addAndGet(PERIOD);
		manager.fail();
		Throwable failure=assertThrows(CompletionException.class,keeper.kept::join).getCause();
		assertInstanceOf(PeerConnectionException.class,failure);
		assertEquals("unreachable",failure.getCause().getMessage());
	}

	/** A fault is not retried: keeping the peer stops with it. */
	@Test public void aFaultStopsKeepingThePeer() {
		PeerKeeper keeper=keeper(TRANSPORT,"tcp://localhost:1");
		keeper.start(null);
		IllegalStateException fault=new IllegalStateException("bug");
		manager.fail(fault);
		assertSame(fault,assertThrows(CompletionException.class,keeper.kept::join).getCause());
		assertTrue(scheduled.isEmpty(),"nothing more is tried");
	}

	/** An abandoned attempt, as when the manager closes, stops keeping the peer quietly. */
	@Test public void anAbandonedAttemptStopsKeepingThePeer() {
		PeerKeeper keeper=keeper(TRANSPORT,"tcp://localhost:1");
		keeper.start(null);
		manager.fail(new CancellationException("Connection manager closed"));
		assertTrue(keeper.kept.isCancelled());
		assertTrue(scheduled.isEmpty(),"nothing more is tried");
	}

	/** Stopping while a retry is being scheduled, before the keeper holds it, still cancels it. */
	@Test public void stoppingWhileARetryIsScheduledCancelsIt() {
		CompletableFuture<Void> retry=new CompletableFuture<>();
		AtomicReference<PeerKeeper> stopping=new AtomicReference<>();
		PeerKeeper keeper=new PeerKeeper(manager,TRANSPORT,PeerResolver.parse("tcp://localhost:1"),PERIOD,now::get,
			(delay,task) -> {
				stopping.get().kept.cancel(false);
				return retry;
			});
		stopping.set(keeper);
		keeper.start(null);
		manager.fail();
		assertTrue(retry.isCancelled(),"no retry is left pending");
	}

	private static AMap<AString,ACell> document(AccountKey key) {
		String did="did:web:files.example.com";
		return RT.ensureMap(JSON.parse("""
			{"id": "%1$s",
			 "verificationMethod": [{"id": "#k", "type": "Multikey", "controller": "%1$s", "publicKeyMultibase": "%2$s"}],
			 "authentication": ["#k"],
			 "service": [{"id": "#l", "type": "ConvexLatticeNode", "serviceEndpoint": "tcp://files.example.com:19888"}]}
			""".formatted(did,Multikey.encodePublicKey(key))));
	}

	/** Records targets and completes each attempt and route as the test says. */
	private static class RecordingManager extends LatticeConnectionManager {
		final List<PeerTarget> targets=new ArrayList<>();
		final List<AccountKey> released=new ArrayList<>();
		final List<CompletableFuture<Convex>> attempts=new ArrayList<>();
		final java.util.Map<AccountKey,CompletableFuture<Void>> routes=new java.util.HashMap<>();

		RecordingManager() { super(new MemoryStore(),System::nanoTime); }

		@Override public CompletableFuture<Convex> connectPeer(PeerTarget target) {
			targets.add(target);
			CompletableFuture<Convex> attempt=new CompletableFuture<>();
			attempts.add(attempt);
			return attempt;
		}

		@Override public CompletableFuture<Void> whenDisconnected(AccountKey key) {
			return routes.computeIfAbsent(key,k -> new CompletableFuture<>());
		}

		@Override public void releasePeer(AccountKey key) { released.add(key); }

		void fail() { fail(new PeerConnectionException("unreachable")); }
		void fail(Throwable error) { attempts.get(attempts.size()-1).completeExceptionally(error); }
		void admit(AccountKey key) { attempts.get(attempts.size()-1).complete(new StubPeer(key)); }
		void loseRoute(AccountKey key) { routes.remove(key).complete(null); }
	}

	private static class StubPeer extends Convex {
		StubPeer(AccountKey key) { super(null,null); setVerifiedPeer(key); }
		@Override public boolean isConnected() { return true; }
		@Override public void close() {}
		@Override public void reconnect() {}
		@Override public InetSocketAddress getHostAddress() { return null; }
		@Override public String toString() { return "Keeper test peer"; }
		@Override public CompletableFuture<Result> transact(SignedData<ATransaction> tx) { throw new UnsupportedOperationException(); }
		@Override public CompletableFuture<Result> messageRaw(Blob message) { throw new UnsupportedOperationException(); }
		@Override public CompletableFuture<Result> message(Message message) { throw new UnsupportedOperationException(); }
		@Override public <T extends ACell> CompletableFuture<T> acquire(Hash hash,AStore store) { throw new UnsupportedOperationException(); }
		@Override public CompletableFuture<Result> requestStatus() { throw new UnsupportedOperationException(); }
		@Override protected CompletableFuture<Result> sendChallenge(SignedData<ACell> data) { throw new UnsupportedOperationException(); }
		@Override public CompletableFuture<Result> query(ACell query,Address address) { throw new UnsupportedOperationException(); }
	}
}
