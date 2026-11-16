package convex.net.ingress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cvm.Address;
import convex.core.data.Blob;
import convex.core.message.AConnection;
import convex.core.message.LocalConnection;
import convex.core.message.Message;
import convex.net.ingress.IngressChannel.State;

/**
 * Ingress with real workers. Waits are on latches, futures and the turn observer,
 * never on time; deadlines fire from a timer the test drives.
 */
public class IngressTest {

	private static final long WAIT = 10;

	private final List<Ingress> ingresses = new ArrayList<>();

	@AfterEach
	public void closeAll() {
		for (Ingress i : ingresses) i.close();
	}

	private Ingress ingress(IngressLimits limits, Predicate<AConnection> priority, Ingress.Timer timer) {
		Ingress i = new Ingress("test", limits, priority, e -> {}, timer);
		ingresses.add(i);
		return i;
	}

	private Ingress ingress(IngressLimits limits) {
		return ingress(limits, c -> false, new ManualTimer());
	}

	private static IngressLimits limits(int workers, int reserved) {
		return new IngressLimits(workers, reserved, 1000, 1_000_000, 10_000_000, 1_000_000, 1_000_000, 1, 1000);
	}

	/** Deadline tasks the test fires itself. */
	static final class ManualTimer implements Ingress.Timer {
		final List<Runnable> tasks = new CopyOnWriteArrayList<>();
		final CountDownLatch scheduled = new CountDownLatch(1);

		@Override
		public Runnable schedule(Runnable task, long delayMillis) {
			tasks.add(task);
			scheduled.countDown();
			return () -> tasks.remove(task);
		}

		@Override
		public void close() {
		}

		void fireAll() {
			for (Runnable r : new ArrayList<>(tasks)) {
				tasks.remove(r);
				r.run();
			}
		}
	}

	/** A route call held until released. */
	static final class Gate {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);

		void pass() {
			entered.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		void awaitEntered() throws InterruptedException {
			assertTrue(entered.await(WAIT, TimeUnit.SECONDS));
		}

		void open() {
			release.countDown();
		}
	}

	/** Server end of a local pair; replies reach the given list. */
	private static AConnection connection(List<Message> replies) {
		LocalConnection client = LocalConnection.createPair(m -> replies.add(m), m -> true);
		return client.getPaired();
	}

	private static AConnection connection() {
		return connection(new CopyOnWriteArrayList<>());
	}

	private static Message raw(AConnection c, int bytes) {
		return Message.create(c, Blob.wrap(new byte[bytes]));
	}

	private static Message query(AConnection c, long id) {
		return Message.createQuery(id, "1", Address.ZERO).withConnection(c);
	}

	/** Completes when a channel's turn ends with it idle. Set before what makes it run. */
	private static CompletableFuture<Void> idle(Ingress ingress, IngressChannel c) {
		CompletableFuture<Void> f = new CompletableFuture<>();
		ingress.setTurnObserver(ch -> {
			if (ch == c && ingress.getState(c) == State.IDLE) f.complete(null);
		});
		return f;
	}

	private static Function<Message, Predicate<Message>> recording(List<Message> routed) {
		return m -> {
			routed.add(m);
			return null;
		};
	}

	@Test
	public void testFifoPerConnection() throws Exception {
		Ingress ingress = ingress(limits(3, 0));
		List<Message> routed = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch done = new CountDownLatch(200);
		AConnection conn = connection();
		IngressChannel c = ingress.register(conn, m -> {
			routed.add(m);
			done.countDown();
			return null;
		}, () -> {});
		List<Message> sent = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			Message m = raw(conn, 10);
			sent.add(m);
			c.admit(m);
		}
		assertTrue(done.await(WAIT, TimeUnit.SECONDS));
		assertEquals(sent, routed);
	}

	@Test
	public void testPauseAndResumeAtLowWater() throws Exception {
		Ingress ingress = ingress(new IngressLimits(1, 0, 4, 1_000_000, 10_000_000, 1_000_000, 1_000_000, 1, 1000));
		AConnection conn = connection();
		Gate gate = new Gate();
		AtomicInteger first = new AtomicInteger();
		AtomicInteger resumes = new AtomicInteger();
		IngressChannel c = ingress.register(conn, m -> {
			if (first.getAndIncrement() == 0) gate.pass();
			return null;
		}, resumes::incrementAndGet);

		assertTrue(c.admit(raw(conn, 10)));
		gate.awaitEntered();
		assertTrue(c.admit(raw(conn, 10)));
		assertTrue(c.admit(raw(conn, 10)));
		assertFalse(c.admit(raw(conn, 10)), "the fourth waiting message reaches the limit");
		assertFalse(c.admit(raw(conn, 10)), "a message framed after the pause is still taken");
		assertEquals(50, ingress.getTotalBytes());

		CompletableFuture<Void> idle = idle(ingress, c);
		gate.open();
		idle.get(WAIT, TimeUnit.SECONDS);
		assertEquals(1, resumes.get(), "resumed once, at half the limit");
		assertEquals(0, ingress.getTotalBytes());
		assertEquals(5, first.get());
	}

	@Test
	public void testTotalBytesPauseEveryConnectionAndResumeAll() throws Exception {
		Ingress ingress = ingress(new IngressLimits(1, 0, 1000, 1_000_000, 100, 1_000_000, 1_000_000, 1, 1000));
		AConnection ca = connection(), cb = connection();
		Gate gate = new Gate();
		AtomicInteger aCalls = new AtomicInteger();
		AtomicInteger resumesA = new AtomicInteger(), resumesB = new AtomicInteger();
		IngressChannel a = ingress.register(ca, m -> {
			if (aCalls.getAndIncrement() == 0) gate.pass();
			return null;
		}, resumesA::incrementAndGet);
		IngressChannel b = ingress.register(cb, m -> null, resumesB::incrementAndGet);

		assertTrue(a.admit(raw(ca, 40)));
		gate.awaitEntered();
		assertTrue(b.admit(raw(cb, 40)));
		assertFalse(b.admit(raw(cb, 40)), "the total limit pauses the connection that reaches it");
		assertFalse(a.admit(raw(ca, 10)), "and any other that admits while it is reached");

		CompletableFuture<Void> idleB = idle(ingress, b);
		gate.open();
		idleB.get(WAIT, TimeUnit.SECONDS);
		assertEquals(1, resumesA.get());
		assertEquals(1, resumesB.get());
		assertEquals(0, ingress.getTotalBytes());
	}

	@Test
	public void testOrdinaryLaneLeavesReservedWorker() throws Exception {
		Set<AConnection> priority = ConcurrentHashMap.newKeySet();
		Ingress ingress = ingress(limits(3, 1), priority::contains, new ManualTimer());
		Gate g1 = new Gate(), g2 = new Gate();
		AConnection o1 = connection(), o2 = connection(), o3 = connection(), p = connection();
		priority.add(p);
		IngressChannel c1 = ingress.register(o1, m -> { g1.pass(); return null; }, () -> {});
		IngressChannel c2 = ingress.register(o2, m -> { g2.pass(); return null; }, () -> {});
		CompletableFuture<Message> o3Routed = new CompletableFuture<>();
		IngressChannel c3 = ingress.register(o3, m -> { o3Routed.complete(m); return null; }, () -> {});
		CompletableFuture<Message> pRouted = new CompletableFuture<>();
		IngressChannel cp = ingress.register(p, m -> { pRouted.complete(m); return null; }, () -> {});

		c1.admit(raw(o1, 10));
		c2.admit(raw(o2, 10));
		g1.awaitEntered();
		g2.awaitEntered();
		assertEquals(2, ingress.getOrdinaryServing());

		c3.admit(raw(o3, 10));
		assertEquals(State.QUEUED, ingress.getState(c3));
		assertFalse(ingress.isIdleWhileEligible());

		cp.admit(raw(p, 10));
		pRouted.get(WAIT, TimeUnit.SECONDS);
		assertEquals(State.QUEUED, ingress.getState(c3), "no third ordinary turn while two run");
		assertFalse(o3Routed.isDone());

		g1.open();
		o3Routed.get(WAIT, TimeUnit.SECONDS);
		g2.open();
	}

	@Test
	public void testLaneReadWhenConnectionRejoins() throws Exception {
		Set<AConnection> priority = ConcurrentHashMap.newKeySet();
		Ingress ingress = ingress(limits(2, 1), priority::contains, new ManualTimer());
		Gate gate = new Gate();
		AConnection blocker = connection(), q = connection();
		IngressChannel cb = ingress.register(blocker, m -> { gate.pass(); return null; }, () -> {});
		List<Message> routed = new CopyOnWriteArrayList<>();
		IngressChannel cq = ingress.register(q, recording(routed), () -> {});

		CompletableFuture<Void> qIdle = idle(ingress, cq);
		cq.admit(raw(q, 10));
		qIdle.get(WAIT, TimeUnit.SECONDS);

		cb.admit(raw(blocker, 10));
		gate.awaitEntered(); // the one ordinary worker is held

		priority.add(q);
		qIdle = idle(ingress, cq);
		cq.admit(raw(q, 10));
		qIdle.get(WAIT, TimeUnit.SECONDS);
		assertEquals(2, routed.size(), "verified meanwhile, the connection is served in the priority lane");
		gate.open();
	}

	@Test
	public void testBlockedConnectionWaitsForCapacityOthersServed() throws Exception {
		ManualTimer timer = new ManualTimer();
		Ingress ingress = ingress(limits(1, 0), c -> false, timer);
		QueueDestination d = new QueueDestination(1, 0);
		Message filler = raw(null, 1);
		assertTrue(d.tryOffer(filler));

		AConnection ca = connection(), cb = connection();
		IngressChannel a = ingress.register(ca, m -> d.tryOffer(m) ? null : d, () -> {});
		CompletableFuture<Message> bRouted = new CompletableFuture<>();
		IngressChannel b = ingress.register(cb, m -> { bRouted.complete(m); return null; }, () -> {});

		Message a1 = raw(ca, 10);
		a.admit(a1);
		assertTrue(timer.scheduled.await(WAIT, TimeUnit.SECONDS), "a refused message waits with a deadline");
		Message a2 = raw(ca, 10);
		a.admit(a2);

		b.admit(raw(cb, 10));
		bRouted.get(WAIT, TimeUnit.SECONDS);
		assertEquals(2, ingress.getWaiting(a), "the blocked message and the one behind it wait");

		CompletableFuture<Void> aIdle = idle(ingress, a);
		assertSame(filler, d.poll(0, TimeUnit.MILLISECONDS)); // room: a1 goes in
		assertSame(a1, d.poll(WAIT, TimeUnit.SECONDS)); // room again: a2 goes in
		aIdle.get(WAIT, TimeUnit.SECONDS);
		assertSame(a2, d.poll(0, TimeUnit.MILLISECONDS));
		assertTrue(timer.tasks.isEmpty(), "the deadline is cancelled once the message is placed");
		assertEquals(0, ingress.getTotalBytes());
	}

	@Test
	public void testDeadlineRefusesWithLoad() throws Exception {
		ManualTimer timer = new ManualTimer();
		Ingress ingress = ingress(limits(1, 0), c -> false, timer);
		QueueDestination d = new QueueDestination(1, 0);
		assertTrue(d.tryOffer(raw(null, 1)));

		List<Message> replies = new CopyOnWriteArrayList<>();
		AConnection conn = connection(replies);
		IngressChannel c = ingress.register(conn, m -> d.tryOffer(m) ? null : d, () -> {});
		c.admit(query(conn, 7));
		assertTrue(timer.scheduled.await(WAIT, TimeUnit.SECONDS));

		CompletableFuture<Void> idle = idle(ingress, c);
		timer.fireAll();
		idle.get(WAIT, TimeUnit.SECONDS);
		assertEquals(1, replies.size());
		Result r = replies.get(0).toResult();
		assertEquals(ErrorCodes.LOAD, r.getErrorCode());
		assertEquals(0, ingress.getTotalBytes());
		assertEquals(1, d.size(), "the refused message never reached the destination");
	}

	@Test
	public void testRetryPredicateRunsOffWorkerInOrder() throws Exception {
		Ingress ingress = ingress(limits(1, 0));
		List<Message> replies = new CopyOnWriteArrayList<>();
		AConnection conn = connection(replies);
		Gate gate = new Gate();
		List<Message> routed = new CopyOnWriteArrayList<>();
		Message first = query(conn, 3);
		CompletableFuture<Boolean> retryOnWorker = new CompletableFuture<>();
		IngressChannel c = ingress.register(conn, m -> {
			routed.add(m);
			if (m != first) return null;
			return x -> {
				retryOnWorker.complete(Ingress.isWorkerThread());
				gate.pass();
				return false;
			};
		}, () -> {});

		c.admit(first);
		gate.awaitEntered();
		Message second = raw(conn, 10);
		CompletableFuture<Void> idle = idle(ingress, c);
		c.admit(second);
		assertEquals(1, routed.size(), "the next message waits for the retry");
		gate.open();
		idle.get(WAIT, TimeUnit.SECONDS);
		assertFalse(retryOnWorker.get(), "a retry predicate runs on a thread of its own");
		assertEquals(List.of(first, second), routed);
		assertEquals(ErrorCodes.LOAD, replies.get(0).toResult().getErrorCode());
		assertEquals(0, ingress.getTotalBytes());
	}

	@Test
	public void testThrowingRouteContainedToItsMessage() throws Exception {
		Ingress ingress = ingress(limits(1, 0));
		List<Message> replies = new CopyOnWriteArrayList<>();
		AConnection conn = connection(replies);
		Message bad = query(conn, 9);
		List<Message> routed = new CopyOnWriteArrayList<>();
		IngressChannel c = ingress.register(conn, m -> {
			if (m == bad) throw new IllegalStateException("Test failure");
			routed.add(m);
			return null;
		}, () -> {});
		CompletableFuture<Void> idle = idle(ingress, c);
		c.admit(bad);
		Message good = raw(conn, 10);
		c.admit(good);
		idle.get(WAIT, TimeUnit.SECONDS);
		assertEquals(List.of(good), routed);
		assertEquals(ErrorCodes.PEER, replies.get(0).toResult().getErrorCode());
	}

	@Test
	public void testJvmFailureClosesConnectionAndIsPassedOn() throws Exception {
		CompletableFuture<Throwable> passed = new CompletableFuture<>();
		Ingress ingress = new Ingress("test", limits(1, 0), c -> false, passed::complete, new ManualTimer());
		ingresses.add(ingress);
		AConnection conn = connection();
		IngressChannel c = ingress.register(conn, m -> { throw new InternalError("Test JVM failure"); }, () -> {});
		c.admit(raw(conn, 10));
		assertTrue(passed.get(WAIT, TimeUnit.SECONDS) instanceof InternalError);
		assertTrue(conn.isClosed());
	}

	@Test
	public void testDisconnectReleasesWaitingAndRunning() throws Exception {
		Ingress ingress = ingress(limits(1, 0));
		Gate gate = new Gate();
		AConnection ca = connection(), cb = connection();
		List<Message> routed = new CopyOnWriteArrayList<>();
		IngressChannel a = ingress.register(ca, m -> {
			routed.add(m);
			gate.pass();
			return null;
		}, () -> {});
		IngressChannel b = ingress.register(cb, recording(routed), () -> {});

		a.admit(raw(ca, 10));
		gate.awaitEntered();
		a.admit(raw(ca, 20));
		b.admit(raw(cb, 30));
		assertEquals(60, ingress.getTotalBytes());

		b.disconnect();
		assertEquals(30, ingress.getTotalBytes(), "a queued channel is released at once");
		assertFalse(b.admit(raw(cb, 30)), "nothing is admitted once disconnected");

		CompletableFuture<Void> aIdle = idle(ingress, a);
		a.disconnect();
		gate.open();
		aIdle.get(WAIT, TimeUnit.SECONDS);
		assertEquals(1, routed.size(), "a running channel stops at the end of its message");
		assertEquals(0, ingress.getTotalBytes());
		a.disconnect(); // idempotent
		assertEquals(0, ingress.getTotalBytes());
	}

	@Test
	public void testTurnBoundedByQuantum() throws Exception {
		// Quantum 2048, messages of 1536: a turn serves at most two
		Ingress ingress = ingress(new IngressLimits(1, 0, 1000, 1_000_000, 10_000_000, 65536, 2048, 512, 1000));
		Gate gate = new Gate();
		AConnection cg = connection(), ca = connection(), cb = connection();
		List<AConnection> order = new CopyOnWriteArrayList<>();
		IngressChannel g = ingress.register(cg, m -> { gate.pass(); return null; }, () -> {});
		Function<Message, Predicate<Message>> route = m -> {
			order.add(m.getConnection());
			return null;
		};
		IngressChannel a = ingress.register(ca, route, () -> {});
		IngressChannel b = ingress.register(cb, route, () -> {});

		g.admit(raw(cg, 10));
		gate.awaitEntered();
		for (int i = 0; i < 6; i++) a.admit(raw(ca, 1536));
		for (int i = 0; i < 6; i++) b.admit(raw(cb, 1536));
		CompletableFuture<Void> bIdle = idle(ingress, b);
		gate.open();
		bIdle.get(WAIT, TimeUnit.SECONDS);

		assertEquals(12, order.size());
		int run = 0, longest = 0;
		AConnection last = null;
		for (AConnection x : order) {
			run = (x == last) ? run + 1 : 1;
			last = x;
			longest = Math.max(longest, run);
		}
		assertEquals(2, longest, "each turn serves at most its quantum plus one message");
	}

	@Test
	public void testCloseStopsWorkersAndAdmission() throws Exception {
		Ingress ingress = new Ingress("test", limits(3, 1), c -> false, e -> {}, new ManualTimer());
		AConnection conn = connection();
		CompletableFuture<Boolean> onWorker = new CompletableFuture<>();
		IngressChannel c = ingress.register(conn, m -> { onWorker.complete(Ingress.isWorkerThread()); return null; }, () -> {});
		c.admit(raw(conn, 10));
		assertTrue(onWorker.get(WAIT, TimeUnit.SECONDS));
		assertFalse(Ingress.isWorkerThread());

		ingress.close();
		assertTrue(ingress.isStopped(), "close returns once the workers have stopped");
		assertFalse(c.admit(raw(conn, 10)));
		assertEquals(0, ingress.getTotalBytes());
		IngressChannel late = ingress.register(conn, m -> null, () -> {});
		assertFalse(late.admit(raw(conn, 10)));
		ingress.close(); // idempotent
	}
}
